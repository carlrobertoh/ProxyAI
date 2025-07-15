package ee.carlrobert.codegpt.settings.migration

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.models.ModelRegistry
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.models.ModelSettingsState
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.anthropic.AnthropicSettings
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTAvailableModels
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.google.GoogleSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.llm.client.google.models.GoogleModel

object LegacySettingsMigration {

    private val logger = thisLogger()

    fun migrateIfNeeded() {
        try {
            val selectedService = GeneralSettings.getCurrentState().selectedService
            if (selectedService != null) {
                val migratedState = createMigratedState(selectedService)
                service<ModelSettings>().loadState(migratedState)
            }
        } catch (exception: Exception) {
            logger.error("Failed to migrate legacy settings", exception)
        }
    }

    private fun createMigratedState(selectedService: ServiceType): ModelSettingsState {
        val state = ModelSettingsState()
        val chatModel = getLegacyChatModelForService(selectedService)
        state.setModelSelection(FeatureType.CHAT, chatModel, selectedService)
        state.setModelSelection(FeatureType.AUTO_APPLY, chatModel, selectedService)
        state.setModelSelection(FeatureType.COMMIT_MESSAGE, chatModel, selectedService)
        state.setModelSelection(FeatureType.EDIT_CODE, chatModel, selectedService)
        state.setModelSelection(FeatureType.LOOKUP, chatModel, selectedService)

        val codeModel = getLegacyCodeModelForService(selectedService)
        state.setModelSelection(FeatureType.CODE_COMPLETION, codeModel, selectedService)
        if (selectedService == ServiceType.PROXYAI) {
            state.setModelSelection(FeatureType.NEXT_EDIT, ModelRegistry.ZETA, ServiceType.PROXYAI)
        } else {
            state.setModelSelection(FeatureType.NEXT_EDIT, null, selectedService)
        }

        return state
    }

    private fun getLegacyChatModelForService(serviceType: ServiceType): String {
        return try {
            when (serviceType) {
                ServiceType.PROXYAI -> {
                    val settings = service<CodeGPTServiceSettings>()
                    settings.state.chatCompletionSettings.model
                        ?: ModelRegistry.GEMINI_FLASH_2_5
                }

                ServiceType.OPENAI -> {
                    OpenAISettings.getCurrentState().model
                }

                ServiceType.ANTHROPIC -> {
                    AnthropicSettings.getCurrentState().model
                        ?: ModelRegistry.CLAUDE_SONNET_4_20250514
                }

                ServiceType.GOOGLE -> {
                    service<GoogleSettings>().state.model
                        ?: GoogleModel.GEMINI_PRO.code
                }

                ServiceType.OLLAMA -> {
                    service<OllamaSettings>().state.model
                        ?: "llama3.2"
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
                    service<CustomServicesSettings>().state.services
                        .map { it.chatCompletionSettings.body["model"] as String }
                        .lastOrNull() ?: ""
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not get legacy model for service $serviceType, using default", e)
            getDefaultModelForService(serviceType)
        }
    }

    private fun getLegacyCodeModelForService(serviceType: ServiceType): String? {
        return try {
            when (serviceType) {
                ServiceType.PROXYAI -> {
                    service<CodeGPTServiceSettings>().state.codeCompletionSettings.model
                }

                ServiceType.OPENAI -> {
                    ModelRegistry.GPT_3_5_TURBO_INSTRUCT
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
                    service<CustomServicesSettings>().state.services
                        .map { it.codeCompletionSettings.body["model"] as String }
                        .lastOrNull() ?: ""
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not get legacy model for service $serviceType, using default", e)
            getDefaultModelForService(serviceType)
        }
    }

    private fun getDefaultModelForService(serviceType: ServiceType): String {
        return when (serviceType) {
            ServiceType.PROXYAI -> ModelRegistry.GEMINI_FLASH_2_5
            ServiceType.OPENAI -> ModelRegistry.GPT_4O
            ServiceType.ANTHROPIC -> ModelRegistry.CLAUDE_SONNET_4_20250514
            ServiceType.GOOGLE -> ModelRegistry.GEMINI_2_0_FLASH
            ServiceType.OLLAMA -> ModelRegistry.LLAMA_3_2
            ServiceType.LLAMA_CPP -> ModelRegistry.LLAMA_3_2_3B_INSTRUCT
            ServiceType.CUSTOM_OPENAI -> ModelRegistry.GPT_4O
        }
    }
}