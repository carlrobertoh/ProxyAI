package ee.carlrobert.codegpt.settings.migration

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.models.Devstral2
import ee.carlrobert.codegpt.settings.models.ModelCatalog
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.models.ModelSettingsState
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.anthropic.AnthropicSettings
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ai.koog.prompt.executor.clients.google.GoogleModels

object LegacySettingsMigration {

    private val logger = thisLogger()

    fun migrateIfNeeded(): ModelSettingsState? {
        return try {
            val generalState = GeneralSettings.getCurrentState()
            val selectedService = generalState.selectedService

            if (selectedService != null) {
                generalState.selectedService = null
                createMigratedState(selectedService)
            } else {
                null
            }
        } catch (exception: Exception) {
            logger.error("Failed to migrate legacy settings", exception)
            null
        }
    }

    private fun createMigratedState(selectedService: ServiceType): ModelSettingsState {
        val modelSettings = service<ModelSettings>()
        return ModelSettingsState().apply {
            val chatModel = getLegacyChatModelForService(selectedService)
            val agentModel = if (modelSettings.isFeatureSupportedByProvider(
                    FeatureType.AGENT,
                    selectedService
                )
            ) {
                getLegacyAgentModelForService(selectedService, chatModel)
            } else {
                null
            }

            setModelSelection(FeatureType.AGENT, agentModel, selectedService)
            setModelSelection(FeatureType.CHAT, chatModel, selectedService)
            setModelSelection(FeatureType.AUTO_APPLY, chatModel, selectedService)
            setModelSelection(FeatureType.COMMIT_MESSAGE, chatModel, selectedService)
            setModelSelection(FeatureType.INLINE_EDIT, chatModel, selectedService)
            setModelSelection(FeatureType.LOOKUP, chatModel, selectedService)

            val codeModel = getLegacyCodeModelForService(selectedService)
            setModelSelection(FeatureType.CODE_COMPLETION, codeModel, selectedService)

            val nextEditModel = if (selectedService == ServiceType.PROXYAI) {
                ModelCatalog.MERCURY_CODER
            } else {
                resolveAvailableModel(
                    serviceType = selectedService,
                    featureType = FeatureType.NEXT_EDIT
                )
            }
            setModelSelection(FeatureType.NEXT_EDIT, nextEditModel, selectedService)
        }
    }

    private fun getLegacyChatModelForService(serviceType: ServiceType): String {
        return try {
            when (serviceType) {
                ServiceType.PROXYAI -> {
                    val settings = service<CodeGPTServiceSettings>()
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CHAT,
                        preferredModel = settings.state.chatCompletionSettings.model,
                        fallbackModel = ModelCatalog.GEMINI_FLASH_2_5
                    )
                }

                ServiceType.OPENAI -> {
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CHAT,
                        preferredModel = OpenAISettings.getCurrentState().model
                    )
                }

                ServiceType.ANTHROPIC -> {
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CHAT,
                        preferredModel = AnthropicSettings.getCurrentState().model
                    )
                }

                ServiceType.GOOGLE -> {
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CHAT,
                        fallbackModel = GoogleModels.Gemini2_5Pro.id
                    )
                }

                ServiceType.OLLAMA -> {
                    val settings = service<OllamaSettings>()
                    settings.state.model
                        ?: resolveAvailableModel(
                            serviceType = serviceType,
                            featureType = FeatureType.CHAT,
                            fallbackModel = ModelCatalog.LLAMA_3_2
                        )
                }

                ServiceType.LLAMA_CPP -> {
                    val llamaSettings = LlamaSettings.getCurrentState()
                    if (llamaSettings.isUseCustomModel) {
                        llamaSettings.customLlamaModelPath
                    } else {
                        llamaSettings.huggingFaceModel.name
                    }
                }

                ServiceType.CUSTOM_OPENAI -> {
                    val preferredServiceId = service<CustomServicesSettings>().state.services
                        .lastOrNull()
                        ?.id
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CHAT,
                        preferredModel = preferredServiceId
                    )
                }

                ServiceType.MISTRAL -> {
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CHAT,
                        fallbackModel = Devstral2.id
                    )
                }

                ServiceType.INCEPTION -> {
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CHAT,
                        fallbackModel = ModelCatalog.MERCURY_CODER
                    )
                }
            } ?: error("Could not resolve legacy chat model for $serviceType")
        } catch (e: Exception) {
            logger.warn("Failed to get legacy chat model for $serviceType", e)
            throw e
        }
    }

    private fun getLegacyAgentModelForService(
        serviceType: ServiceType,
        fallbackModel: String
    ): String {
        val modelSettings = service<ModelSettings>()
        if (!modelSettings.isFeatureSupportedByProvider(FeatureType.AGENT, serviceType)) {
            return fallbackModel
        }

        if (serviceType == ServiceType.PROXYAI) {
            return ModelCatalog.PROXYAI_AUTO
        }

        return modelSettings.getAvailableModels(FeatureType.AGENT)
            .firstOrNull { it.provider == serviceType }
            ?.model
            ?: fallbackModel
    }

    private fun getLegacyCodeModelForService(serviceType: ServiceType): String? {
        return try {
            when (serviceType) {
                ServiceType.PROXYAI -> {
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CODE_COMPLETION,
                        preferredModel = service<CodeGPTServiceSettings>().state.codeCompletionSettings.model,
                        fallbackModel = ModelCatalog.MERCURY_CODER
                    )
                }

                ServiceType.OPENAI -> {
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CODE_COMPLETION,
                        fallbackModel = ModelCatalog.GPT_3_5_TURBO_INSTRUCT
                    )
                }

                ServiceType.ANTHROPIC -> {
                    null
                }

                ServiceType.GOOGLE -> {
                    null
                }

                ServiceType.OLLAMA -> {
                    service<OllamaSettings>().state.model
                }

                ServiceType.LLAMA_CPP -> {
                    val llamaSettings = LlamaSettings.getCurrentState()
                    if (llamaSettings.isUseCustomModel) {
                        llamaSettings.customLlamaModelPath
                    } else {
                        llamaSettings.huggingFaceModel.name
                    }
                }

                ServiceType.CUSTOM_OPENAI -> {
                    val preferredServiceId = service<CustomServicesSettings>().state.services
                        .lastOrNull()
                        ?.id
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CODE_COMPLETION,
                        preferredModel = preferredServiceId
                    )
                }

                ServiceType.MISTRAL -> {
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CODE_COMPLETION,
                        fallbackModel = ModelCatalog.CODESTRAL_LATEST
                    )
                }

                ServiceType.INCEPTION -> {
                    resolveAvailableModel(
                        serviceType = serviceType,
                        featureType = FeatureType.CODE_COMPLETION,
                        fallbackModel = ModelCatalog.MERCURY_CODER
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to get legacy code model for $serviceType", e)
            null
        }
    }

    private fun resolveAvailableModel(
        serviceType: ServiceType,
        featureType: FeatureType,
        preferredModel: String? = null,
        fallbackModel: String? = null
    ): String? {
        val modelSettings = service<ModelSettings>()
        if (!modelSettings.isFeatureSupportedByProvider(featureType, serviceType)) {
            return null
        }

        if (!preferredModel.isNullOrBlank() &&
            modelSettings.findModel(serviceType, preferredModel) != null
        ) {
            return preferredModel
        }

        return modelSettings.getAvailableModels(featureType)
            .firstOrNull { it.provider == serviceType }
            ?.model
            ?: fallbackModel
    }
}
