package ee.carlrobert.codegpt.settings.models

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBusConnection
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelChangeNotifier
import ee.carlrobert.codegpt.settings.service.ServiceType
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

class ModelSettingsForm(
    private val serviceType: ServiceType? = null,
    private val initialModelSelections: Map<FeatureType, ModelSelection?>? = null,
    private val previousModelSelections: Map<FeatureType, ModelSelection?>? = null
) : Disposable {

    private val settings = ModelSettings.getInstance()
    private val modelButtons = mutableMapOf<FeatureType, ModelSelectionButton>()
    private val messageBusConnection: MessageBusConnection

    data class FeatureGroup(
        val titleKey: String,
        val descriptionKey: String,
        val features: List<FeatureConfig>
    )

    data class FeatureConfig(
        val featureType: FeatureType,
        val labelKey: String
    )

    companion object {
        private val FEATURE_GROUPS = listOf(
            FeatureGroup(
                titleKey = "settings.models.chat.section.title",
                descriptionKey = "settings.models.chat.section.description",
                features = listOf(
                    FeatureConfig(FeatureType.CHAT, "settings.models.chat.label"),
                    FeatureConfig(FeatureType.AUTO_APPLY, "settings.models.autoApply.label"),
                    FeatureConfig(
                        FeatureType.COMMIT_MESSAGE,
                        "settings.models.commitMessages.label"
                    ),
                    FeatureConfig(FeatureType.EDIT_CODE, "settings.models.editCode.label"),
                    FeatureConfig(FeatureType.LOOKUP, "settings.models.nameLookups.label")
                )
            ),
            FeatureGroup(
                titleKey = "settings.models.tab.section.title",
                descriptionKey = "settings.models.tab.section.description",
                features = listOf(
                    FeatureConfig(FeatureType.CODE_COMPLETION, "settings.models.code.label"),
                    FeatureConfig(FeatureType.NEXT_EDIT, "settings.models.nextEdit.label")
                )
            )
        )
    }

    init {
        initializeModelButtons()
        if (serviceType != null) {
            updateNextEditButtonState(serviceType)
        }

        messageBusConnection = ApplicationManager.getApplication().messageBus.connect()
        messageBusConnection.subscribe(
            ModelChangeNotifier.getTopic(),
            object : ModelChangeNotifier {
                override fun chatModelChanged(newModel: String, serviceType: ServiceType) {
                    modelChanged(FeatureType.CHAT, newModel, serviceType)
                }

                override fun codeModelChanged(newModel: String, serviceType: ServiceType) {
                    modelChanged(FeatureType.CODE_COMPLETION, newModel, serviceType)
                }

                override fun autoApplyModelChanged(newModel: String, serviceType: ServiceType) {
                    modelChanged(FeatureType.AUTO_APPLY, newModel, serviceType)
                }

                override fun commitMessageModelChanged(newModel: String, serviceType: ServiceType) {
                    modelChanged(FeatureType.COMMIT_MESSAGE, newModel, serviceType)
                }

                override fun editCodeModelChanged(newModel: String, serviceType: ServiceType) {
                    modelChanged(FeatureType.EDIT_CODE, newModel, serviceType)
                }

                override fun nextEditModelChanged(newModel: String, serviceType: ServiceType) {
                    modelChanged(FeatureType.NEXT_EDIT, newModel, serviceType)
                }

                override fun nameLookupModelChanged(newModel: String, serviceType: ServiceType) {
                    modelChanged(FeatureType.LOOKUP, newModel, serviceType)
                }

                override fun modelChanged(
                    featureType: FeatureType,
                    newModel: String,
                    serviceType: ServiceType
                ) {
                    refreshModelButton(featureType)
                }
            }
        )
    }

    private fun initializeModelButtons() {
        val state = settings.state

        FeatureType.entries.forEach { featureType ->
            modelButtons[featureType] = createModelButton(
                featureType,
                state.getModelSelection(featureType) ?: ModelDetailsState()
            )
        }
    }


    fun createPanel(): DialogPanel {
        return panel {
            FEATURE_GROUPS.forEach { featureGroup ->
                group(CodeGPTBundle.get(featureGroup.titleKey)) {
                    row {
                        text(CodeGPTBundle.get(featureGroup.descriptionKey))
                    }

                    featureGroup.features.forEach { featureConfig ->
                        row {
                            label(CodeGPTBundle.get(featureConfig.labelKey))
                                .widthGroup("modelLabels")
                            cell(createButtonPanel(modelButtons[featureConfig.featureType]!!))
                                .align(Align.FILL)
                        }
                        addPreviousModelRow(this, featureConfig.featureType)
                    }
                }
            }
        }
    }


    private fun createButtonPanel(button: ModelSelectionButton): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)

        val buttonWidth = 400
        button.preferredSize = Dimension(buttonWidth, button.preferredSize.height)
        button.maximumSize = Dimension(buttonWidth, button.preferredSize.height)

        panel.add(button)
        panel.add(Box.createHorizontalGlue())
        return panel
    }

    private fun createModelButton(
        featureType: FeatureType,
        modelState: ModelDetailsState
    ): ModelSelectionButton {
        val models = getModelsForFeature(featureType)

        val selectedModel: ModelSelection? = when {
            initialModelSelections != null -> {
                initialModelSelections[featureType]
            }

            serviceType != null -> {
                if (modelState.provider == serviceType && modelState.model != null) {
                    models.find { it.model == modelState.model && it.provider == serviceType }
                        ?: models.firstOrNull { it.provider == serviceType }
                } else {
                    models.firstOrNull { it.provider == serviceType }
                }
            }

            modelState.model != null -> {
                models.find { it.model == modelState.model && it.provider == modelState.provider }
                    ?: models.find { it.model == modelState.model }
            }

            else -> {
                ModelRegistry.getInstance().getDefaultModelForFeature(featureType)
            }
        }

        return ModelSelectionButton(featureType, selectedModel, serviceType) { model ->
            if (featureType == FeatureType.CODE_COMPLETION) {
                updateNextEditButtonState(model.provider)
            }
        }
    }

    private fun updateNextEditButtonState(codeCompletionProvider: ServiceType) {
        val nextEditButton = modelButtons[FeatureType.NEXT_EDIT] ?: return
        nextEditButton.isEnabled = codeCompletionProvider == ServiceType.PROXYAI
    }

    fun isModified(): Boolean {
        val state = settings.state
        return modelButtons.any { (featureType, button) ->
            state.getModelSelection(featureType)?.let { isModelChanged(button, it) } == true
        }
    }

    private fun isModelChanged(
        button: ModelSelectionButton,
        modelState: ModelDetailsState
    ): Boolean {
        val selectedModel = button.getSelectedModel()
        return selectedModel?.let { model ->
            val modelChanged = model.model != modelState.model
            val providerChanged = model.provider != modelState.provider

            modelChanged || providerChanged
        } == true
    }

    fun applyChanges() {
        modelButtons.forEach { (featureType, button) ->
            applyModelChange(button, featureType)
        }
    }

    private fun applyModelChange(button: ModelSelectionButton, featureType: FeatureType) {
        button.getSelectedModel()?.let { model ->
            settings.setModelWithProvider(featureType, model.model, model.provider)
        }
    }

    fun resetForm() {
        val state = settings.state

        modelButtons.forEach { (featureType, button) ->
            val modelState = state.getModelSelection(featureType)
            modelState?.let { setModelByIdAndProvider(button, featureType, it) }
        }
    }

    private fun refreshModelButton(featureType: FeatureType) {
        val button = modelButtons[featureType] ?: return
        val state = settings.state
        val modelState = state.getModelSelection(featureType) ?: return

        modelState.model?.let { modelId ->
            val models = getModelsForFeature(featureType)
            val selectedModel = if (modelState.provider != null) {
                models.find { it.model == modelId && it.provider == modelState.provider }
            } else {
                models.find { it.model == modelId }
            }

            selectedModel?.let { button.setSelectedModel(it) }
        }
    }

    private fun setModelByIdAndProvider(
        button: ModelSelectionButton,
        featureType: FeatureType,
        modelState: ModelDetailsState
    ) {
        if (modelState.model == null) return

        val models = getModelsForFeature(featureType)

        if (modelState.provider != null) {
            val modelWithProvider =
                models.find { it.model == modelState.model && it.provider == modelState.provider }
            if (modelWithProvider != null) {
                button.setSelectedModel(modelWithProvider)
                return
            }
        }

        models.find { it.model == modelState.model }
            ?.let { model -> button.setSelectedModel(model) }
    }

    private fun getModelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return ModelRegistry.getInstance().getAllModelsForFeature(featureType)
    }

    private fun addPreviousModelRow(
        panel: com.intellij.ui.dsl.builder.Panel,
        featureType: FeatureType
    ) {
        previousModelSelections?.get(featureType)?.let { previousModel ->
            val currentSelection = modelButtons[featureType]?.getSelectedModel()
            if (currentSelection == null || previousModel.model != currentSelection.model || previousModel.provider != currentSelection.provider) {
                panel.row {
                    label("")
                        .widthGroup("modelLabels")
                    text("Previous: ${previousModel.displayName}")
                        .applyToComponent {
                            foreground = com.intellij.ui.JBColor.GRAY
                            font = font.deriveFont(font.size - 1f)
                        }
                }
            }
        }
    }

    override fun dispose() {
        messageBusConnection.disconnect()
    }
}