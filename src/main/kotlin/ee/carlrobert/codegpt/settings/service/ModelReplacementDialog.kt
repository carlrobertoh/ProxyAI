package ee.carlrobert.codegpt.settings.service

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import ee.carlrobert.codegpt.CodeGPTKeys.CODEGPT_USER_DETAILS
import ee.carlrobert.codegpt.settings.models.ModelRegistry
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.models.ModelSettingsForm
import ee.carlrobert.codegpt.util.ApplicationUtil
import javax.swing.JComponent

class ModelReplacementDialog(
    private val project: Project?,
    serviceType: ServiceType,
    private val preferredCustomServiceId: String? = null
) : DialogWrapper(project) {

    private val modelSettingsForm =
        ModelSettingsForm(
            serviceType,
            generateInitialModelSelections(serviceType),
            getCurrentModelSelections()
        )

    var result: DialogResult = DialogResult.CANCEL_ALL
        private set

    init {
        title = "Choose Models for ${serviceType.label}"
        setOKButtonText("Update Models")
        setCancelButtonText("Keep Current Models")

        init()
    }

    override fun createCenterPanel(): JComponent {
        return modelSettingsForm.createPanel()
    }

    override fun doOKAction() {
        result = DialogResult.APPLY_MODELS
        try {
            applyModelChanges()
        } catch (e: Exception) {
            thisLogger().error("Failed to apply model changes", e)
        } finally {
            super.doOKAction()
        }
    }

    override fun doCancelAction() {
        result = DialogResult.KEEP_MODELS
        super.doCancelAction()
    }

    private fun applyModelChanges() {
        modelSettingsForm.applyChanges()
    }

    override fun dispose() {
        modelSettingsForm.dispose()
        super.dispose()
    }

    private fun generateInitialModelSelections(serviceType: ServiceType): Map<FeatureType, ModelSelection?> {
        val registry = ModelRegistry.getInstance()
        return FeatureType.entries.associateWith { featureType ->
            when (serviceType) {
                ServiceType.PROXYAI -> {
                    val userDetails = project?.let { CODEGPT_USER_DETAILS.get(it) }
                    registry.getDefaultModelForFeature(featureType, userDetails?.pricingPlan)
                }

                ServiceType.CUSTOM_OPENAI -> {
                    val models = registry.getAllModelsForFeature(featureType)
                        .filter { it.provider == serviceType }

                    if (!preferredCustomServiceId.isNullOrBlank()) {
                        models.firstOrNull { it.model == preferredCustomServiceId }
                            ?: models.firstOrNull()
                    } else {
                        models.firstOrNull()
                    }
                }

                else -> {
                    registry.getAllModelsForFeature(featureType)
                        .firstOrNull { it.provider == serviceType }
                }
            }
        }
    }

    private fun getCurrentModelSelections(): Map<FeatureType, ModelSelection?> {
        val modelSettings = ModelSettings.getInstance()
        return FeatureType.entries.associateWith { featureType ->
            modelSettings.getModelSelection(featureType)
        }
    }

    companion object {
        fun showDialog(
            serviceType: ServiceType,
            preferredCustomServiceId: String? = null
        ): DialogResult {
            val dialog = ModelReplacementDialog(
                ApplicationUtil.findCurrentProject(),
                serviceType,
                preferredCustomServiceId
            )
            dialog.show()
            return dialog.result
        }

        fun showDialogIfNeeded(
            serviceType: ServiceType,
            preferredCustomServiceId: String? = null
        ): DialogResult {
            return if (shouldShowDialog(serviceType, preferredCustomServiceId)) {
                showDialog(serviceType, preferredCustomServiceId)
            } else {
                DialogResult.KEEP_MODELS
            }
        }

        private fun shouldShowDialog(
            serviceType: ServiceType,
            preferredCustomServiceId: String? = null
        ): Boolean {
            return FeatureType.entries.any { featureType ->
                val registry = service<ModelRegistry>()
                if (!registry.isFeatureSupportedByProvider(
                        featureType,
                        serviceType
                    )
                ) return@any false

                val currentSelection = service<ModelSettings>().getModelSelection(featureType)
                val availableModels = registry.getAllModelsForFeature(featureType)
                    .filter { it.provider == serviceType }

                if (availableModels.isEmpty()) return@any false

                when {
                    currentSelection?.provider != null && currentSelection.provider != serviceType -> true

                    serviceType == ServiceType.CUSTOM_OPENAI && !preferredCustomServiceId.isNullOrBlank() &&
                            currentSelection != null && currentSelection.model != preferredCustomServiceId -> true

                    currentSelection != null && availableModels.none { it.model == currentSelection.model } -> true

                    else -> false
                }
            }
        }
    }
}