package ee.carlrobert.codegpt.settings.models

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.completions.llama.LlamaModel
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.toolwindow.ui.ModelListPopup
import java.awt.Color
import javax.swing.JComponent

class SettingsModelComboBoxAction(
    private val featureType: FeatureType,
    private var currentModel: ModelSelection?,
    private val onModelSelected: (ModelSelection) -> Unit,
    private val serviceType: ServiceType? = null,
    private val showConfigureModels: Boolean = true
) : ComboBoxAction() {

    init {
        updateTemplatePresentation(currentModel)
    }

    fun updateTemplatePresentation(model: ModelSelection?) {
        templatePresentation.text =
            model?.fullDisplayName ?: CodeGPTBundle.get("settings.models.selectModel")
        templatePresentation.icon = model?.let { ModelIcons.getIconForModel(it) }
    }

    fun createCustomComponent(place: String): JComponent {
        return createCustomComponent(templatePresentation, place)
    }

    override fun createCustomComponent(
        presentation: Presentation,
        place: String
    ): JComponent {
        val button = createComboBoxButton(presentation)
        button.foreground = EditorColorsManager.getInstance().globalScheme.defaultForeground
        button.border = null
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        return button
    }

    public override fun createPopupActionGroup(
        button: JComponent,
        dataContext: DataContext
    ): DefaultActionGroup {
        val actionGroup = DefaultActionGroup()
        val models = getModelsForFeature(featureType)
        val groupedModels = models.groupBy { it.provider }

        val cloudProviders = listOf(
            ServiceType.PROXYAI,
            ServiceType.ANTHROPIC,
            ServiceType.OPENAI,
            ServiceType.CUSTOM_OPENAI,
            ServiceType.GOOGLE
        )
        val hasCloudProviders = cloudProviders.any { groupedModels.containsKey(it) }

        if (hasCloudProviders) {
            actionGroup.addSeparator("Cloud")

            cloudProviders.forEach { provider ->
                groupedModels[provider]?.let { providerModels ->
                    when (provider) {
                        ServiceType.PROXYAI -> {
                            val group = DefaultActionGroup.createPopupGroup { "ProxyAI" }
                            group.templatePresentation.icon = Icons.DefaultSmall
                            providerModels.forEach { model ->
                                group.add(createModelAction(model))
                            }
                            actionGroup.add(group)
                        }

                        ServiceType.ANTHROPIC -> {
                            val group = DefaultActionGroup.createPopupGroup { provider.label }
                            group.templatePresentation.icon =
                                ModelIcons.getIconForProvider(provider)
                            providerModels.forEach { model ->
                                group.add(createModelAction(model))
                            }
                            actionGroup.add(group)
                        }

                        else -> {
                            val group = DefaultActionGroup.createPopupGroup { provider.label }
                            group.templatePresentation.icon =
                                ModelIcons.getIconForProvider(provider)
                            providerModels.forEach { model ->
                                group.add(createModelAction(model))
                            }
                            actionGroup.add(group)
                        }
                    }
                }
            }
        }

        val localProviders = listOf(ServiceType.OLLAMA, ServiceType.LLAMA_CPP)
        val hasLocalProviders = localProviders.any { groupedModels.containsKey(it) }

        if (hasLocalProviders) {
            actionGroup.addSeparator("Offline")

            localProviders.forEach { provider ->
                groupedModels[provider]?.let { providerModels ->
                    when (provider) {
                        ServiceType.LLAMA_CPP -> {
                            val llamaDisplayName = getLlamaDisplayName()
                            val llamaAction = object : DumbAwareAction(
                                llamaDisplayName,
                                "",
                                ModelIcons.getIconForProvider(ServiceType.LLAMA_CPP)
                            ) {
                                override fun update(e: AnActionEvent) {
                                    e.presentation.isEnabled =
                                        currentModel?.provider != ServiceType.LLAMA_CPP
                                }

                                override fun actionPerformed(e: AnActionEvent) {
                                    val llamaSettings = ApplicationManager.getApplication()
                                        .service<LlamaSettings>().state
                                    val matchingModel =
                                        providerModels.find { it.model == llamaSettings.huggingFaceModel.name }
                                            ?: providerModels.firstOrNull()

                                    matchingModel?.let {
                                        currentModel = it
                                        updateTemplatePresentation(it)
                                        onModelSelected(it)
                                    }
                                }

                                override fun getActionUpdateThread(): ActionUpdateThread {
                                    return ActionUpdateThread.BGT
                                }
                            }
                            actionGroup.add(llamaAction)
                        }

                        else -> {
                            val group = DefaultActionGroup.createPopupGroup { provider.label }
                            group.templatePresentation.icon =
                                ModelIcons.getIconForProvider(provider)
                            providerModels.forEach { model ->
                                group.add(createModelAction(model))
                            }
                            actionGroup.add(group)
                        }
                    }
                }
            }
        }

        if (showConfigureModels) {
            actionGroup.addSeparator()
            actionGroup.add(object :
                DumbAwareAction("Configure Models", "", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        e.project,
                        ModelSettingsConfigurable::class.java
                    )
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.BGT
                }
            })
        }

        return actionGroup
    }

    public override fun createActionPopup(
        group: DefaultActionGroup,
        context: DataContext,
        disposeCallback: Runnable?
    ): JBPopup {
        val popup = ModelListPopup(group, context)
        if (disposeCallback != null) {
            popup.addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    disposeCallback.run()
                }
            })
        }
        popup.isShowSubmenuOnHover = true
        return popup
    }

    private fun getModelsForFeature(featureType: FeatureType): List<ModelSelection> {
        val allModels = ModelRegistry.getInstance().getAllModelsForFeature(featureType)

        return if (serviceType != null) {
            allModels.filter { it.provider == serviceType }
        } else {
            allModels
        }
    }

    private fun createModelAction(
        model: ModelSelection,
        useFullDisplayName: Boolean = false
    ): AnAction {
        val icon = ModelIcons.getIconForModel(model)
        val displayText = if (useFullDisplayName) model.fullDisplayName else model.displayName

        return object : DumbAwareAction(displayText, "", icon) {
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled =
                    !(model.provider == currentModel?.provider && model.model == currentModel?.model)
            }

            override fun actionPerformed(e: AnActionEvent) {
                currentModel = model
                updateTemplatePresentation(model)
                onModelSelected(model)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
    }

    private fun getLlamaDisplayName(): String {
        val llamaSettings = ApplicationManager.getApplication().service<LlamaSettings>().state
        val huggingFaceModel = llamaSettings.huggingFaceModel
        val llamaModel = LlamaModel.findByHuggingFaceModel(huggingFaceModel)
        return "${llamaModel.label} (${huggingFaceModel.parameterSize}B) / Q${huggingFaceModel.quantization}"
    }
}