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
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTAvailableModels
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTModel
import ee.carlrobert.codegpt.util.ApplicationUtil
import ee.carlrobert.llm.client.codegpt.PricingPlan
import javax.swing.JComponent

class ModelReplacementDialog(
    private val project: Project?,
    serviceType: ServiceType
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

    private fun generateInitialModelSelections(serviceType: ServiceType): Map<FeatureType, ModelSelection?>? {
        val registry = ModelRegistry.getInstance()

        return when (serviceType) {
            ServiceType.PROXYAI -> {
                val userDetails = project?.let { CODEGPT_USER_DETAILS.get(it) }
                FeatureType.entries.associateWith { featureType ->
                    registry.getDefaultModelForFeature(featureType, userDetails?.pricingPlan)
                }
            }

            else -> {
                FeatureType.entries.associateWith { featureType ->
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
        fun showDialog(serviceType: ServiceType): DialogResult {
            val dialog = ModelReplacementDialog(ApplicationUtil.findCurrentProject(), serviceType)
            dialog.show()
            return dialog.result
        }

        fun showDialogIfNeeded(serviceType: ServiceType): DialogResult {
            return if (shouldShowDialog(serviceType)) {
                showDialog(serviceType)
            } else {
                DialogResult.KEEP_MODELS
            }
        }

        private fun shouldShowDialog(serviceType: ServiceType): Boolean {
            if (serviceType == ServiceType.PROXYAI) {
                val project = ApplicationUtil.findCurrentProject()
                val userDetails = project?.let { CODEGPT_USER_DETAILS.get(it) }
                val modelSettings = service<ModelSettings>()
                val registry = service<ModelRegistry>()

                return FeatureType.entries.any { featureType ->
                    val currentSelection = modelSettings.getModelSelection(featureType)
                    val suggestedSelection =
                        registry.getDefaultModelForFeature(featureType, userDetails?.pricingPlan)

                    when {
                        currentSelection == null -> true
                        currentSelection.provider != serviceType -> true
                        currentSelection.model != suggestedSelection.model -> {
                            val suggestedModelAccessible =
                                CodeGPTAvailableModels.findByCode(suggestedSelection.model)?.let {
                                    isModelAccessible(it, userDetails?.pricingPlan)
                                } == true
                            val currentModelNotAccessible =
                                CodeGPTAvailableModels.findByCode(currentSelection.model)?.let {
                                    !isModelAccessible(it, userDetails?.pricingPlan)
                                } == true

                            suggestedModelAccessible || currentModelNotAccessible
                        }

                        else -> false
                    }
                }
            }

            return FeatureType.entries.any { featureType ->
                val currentSelection = service<ModelSettings>().getModelSelection(featureType)
                val availableModels = service<ModelRegistry>().getAllModelsForFeature(featureType)
                    .filter { it.provider == serviceType }

                when {
                    currentSelection == null -> true
                    currentSelection.provider != serviceType -> true
                    availableModels.none { it.model == currentSelection.model } -> true
                    else -> false
                }
            }
        }

        private fun isModelAccessible(model: CodeGPTModel, userPricingPlan: PricingPlan?): Boolean {
            if (userPricingPlan == null) return false
            return userPricingPlan.ordinal >= model.pricingPlan.ordinal
        }
    }
}