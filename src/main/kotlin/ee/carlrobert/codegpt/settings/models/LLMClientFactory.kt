package ee.carlrobert.codegpt.settings.models

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Haiku_4_5
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Opus_4_5
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Sonnet_4_5
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.agent.clients.*
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings

object LLMClientFactory {

    private val supportedServices = setOf(
        ServiceType.PROXYAI,
        ServiceType.OPENAI,
        ServiceType.ANTHROPIC,
        ServiceType.GOOGLE,
        ServiceType.MISTRAL,
        ServiceType.LLAMA_CPP,
        ServiceType.OLLAMA,
        ServiceType.CUSTOM_OPENAI,
        ServiceType.INCEPTION,
    )

    fun supportedServiceTypes(): Set<ServiceType> = supportedServices

    fun createClient(serviceType: ServiceType, featureType: FeatureType): LLMClient {
        return when (serviceType) {
            ServiceType.PROXYAI -> {
                val apiKey = getCredential(CredentialKey.CodeGptApiKey) ?: ""
                val baseUrl = System.getProperty("proxyai.baseUrl")
                    ?: ProxyAIClientSettings.DEFAULT_BASE_URL
                ProxyAILLMClient(
                    apiKey = apiKey,
                    settings = ProxyAIClientSettings(baseUrl = baseUrl),
                    baseClient = HttpClientProvider.createHttpClient()
                )
            }

            ServiceType.OPENAI -> {
                val apiKey = getCredential(CredentialKey.OpenaiApiKey) ?: ""
                val baseUrl = System.getProperty("openai.baseUrl") ?: "https://api.openai.com"
                CodeGPTOpenAILLMClient(
                    apiKey = apiKey,
                    organization = OpenAISettings.getCurrentState().organization,
                    baseUrl = baseUrl,
                    model = resolveOpenAICodeCompletionModel(),
                    baseClient = HttpClientProvider.createHttpClient()
                )
            }

            ServiceType.ANTHROPIC -> {
                val apiKey = getCredential(CredentialKey.AnthropicApiKey) ?: ""
                val baseUrl = System.getProperty("anthropic.baseUrl")
                    ?: "https://api.anthropic.com"
                val modelVersionsMap = mapOf(
                    Opus_4_6 to "claude-opus-4-6",
                    Opus_4_5 to "claude-opus-4-5-20251101",
                    Sonnet_4_6 to "claude-sonnet-4-6",
                    Sonnet_4_5 to "claude-sonnet-4-5-20250929",
                    Haiku_4_5 to "claude-haiku-4-5-20251001",
                )

                AnthropicLLMClient(
                    apiKey = apiKey,
                    baseClient = HttpClientProvider.createHttpClient(),
                    settings = AnthropicClientSettings(
                        modelVersionsMap = modelVersionsMap,
                        baseUrl = baseUrl
                    )
                )
            }

            ServiceType.GOOGLE -> {
                val apiKey = getCredential(CredentialKey.GoogleApiKey) ?: ""
                val baseUrl =
                    System.getProperty("google.baseUrl")
                        ?: "https://generativelanguage.googleapis.com"
                GoogleLLMClient(
                    apiKey = apiKey,
                    settings = GoogleClientSettings(baseUrl = baseUrl),
                    baseClient = HttpClientProvider.createHttpClient()
                )
            }

            ServiceType.MISTRAL -> {
                val apiKey = getCredential(CredentialKey.MistralApiKey) ?: ""
                val baseUrl = System.getProperty("mistral.baseUrl")
                    ?: "https://api.mistral.ai"
                CodeGPTMistralAILLMClient(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = currentCodeCompletionModel(),
                    baseClient = HttpClientProvider.createHttpClient()
                )
            }

            ServiceType.INCEPTION -> {
                val apiKey = getCredential(CredentialKey.InceptionApiKey) ?: ""
                InceptionAILLMClient(apiKey, baseClient = HttpClientProvider.createHttpClient())
            }

            ServiceType.LLAMA_CPP -> {
                val settings = service<LlamaSettings>().state
                LlamaCppLLMClient(
                    baseUrl = System.getProperty("llama.baseUrl")
                        ?: settings.serverPort?.let { "http://localhost:$it" }
                        ?: "http://localhost:8080",
                    state = settings,
                    baseClient = HttpClientProvider.createHttpClient()
                )
            }

            ServiceType.OLLAMA -> {
                val settings = service<OllamaSettings>().state
                val baseUrl = service<OllamaSettings>().state.host
                    ?: System.getProperty("ollama.baseUrl")
                    ?: "http://localhost:11434"
                CodeGPTOllamaClient(
                    settings = settings,
                    baseUrl = baseUrl,
                    apiKey = getCredential(CredentialKey.OllamaApikey),
                    baseClient = HttpClientProvider.createHttpClient()
                )
            }

            ServiceType.CUSTOM_OPENAI -> {
                val state =
                    service<CustomServicesSettings>().customServiceStateForFeatureType(featureType)
                val serviceId = state.id ?: error("No custom service configured")
                val apiKey = getCredential(CredentialKey.CustomServiceApiKeyById(serviceId))
                    ?: error("No API key found for custom service: $serviceId")

                when (featureType) {
                    FeatureType.CODE_COMPLETION -> CustomOpenAILLMClient.fromCodeCompletionSettingsState(
                        apiKey,
                        state.codeCompletionSettings,
                        HttpClientProvider.createHttpClient()
                    )

                    else -> CustomOpenAILLMClient.fromSettingsState(
                        apiKey,
                        state.chatCompletionSettings,
                        HttpClientProvider.createHttpClient()
                    )
                }
            }
        }
    }

    private fun currentCodeCompletionModel(): String {
        return service<ModelSettings>().getModelSelectionForFeature(FeatureType.CODE_COMPLETION).modelId
    }

    private fun resolveOpenAICodeCompletionModel(): String {
        val current =
            service<ModelSettings>().getModelSelectionForFeature(FeatureType.CODE_COMPLETION)
        return if (current.modelId == ModelCatalog.GPT_3_5_TURBO_INSTRUCT) {
            current.modelId
        } else {
            ModelCatalog.GPT_3_5_TURBO_INSTRUCT
        }
    }
}
