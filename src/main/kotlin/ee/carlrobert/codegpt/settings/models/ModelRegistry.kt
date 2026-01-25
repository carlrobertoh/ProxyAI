package ee.carlrobert.codegpt.settings.models

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Haiku_4_5
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Opus_4_5
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Sonnet_4_5
import ai.koog.prompt.executor.clients.google.GoogleModels.Gemini2_5Flash
import ai.koog.prompt.executor.clients.google.GoogleModels.Gemini2_5Pro
import ai.koog.prompt.executor.clients.google.GoogleModels.Gemini3_Pro_Preview
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels.Chat.DevstralMedium
import ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT4_1
import ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT4_1Mini
import ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT5Mini
import ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT5_1
import ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT5_1Codex
import ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT5_2
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.agent.clients.CustomOpenAILLMClient
import ee.carlrobert.codegpt.agent.clients.InceptionAILLMClient
import ee.carlrobert.codegpt.agent.clients.ProxyAILLMClient
import ee.carlrobert.codegpt.completions.llama.LlamaModel
import ee.carlrobert.codegpt.settings.models.ModelRegistry.Companion.MERCURY
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.llm.client.codegpt.PricingPlan
import ee.carlrobert.llm.client.google.models.GoogleModel
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel
import javax.swing.Icon

data class ModelSelection(
    val provider: ServiceType,
    val model: String,
    val displayName: String,
    val icon: Icon? = null,
    val pricingPlan: PricingPlan? = null,
    val llmModel: LLModel? = null,
    val id: String? = "",
) {
    val fullDisplayName: String = if (provider == ServiceType.LLAMA_CPP) {
        displayName
    } else {
        "$provider • $displayName"
    }
}

data class ModelCapability(
    val provider: ServiceType,
    val supportedFeatures: Set<FeatureType>,
    val requiresPricingPlan: PricingPlan? = null
)

@Service
class ModelRegistry {

    private val logger = thisLogger()

    private val providerCapabilities = mapOf(
        ServiceType.PROXYAI to ModelCapability(
            ServiceType.PROXYAI,
            setOf(
                FeatureType.AGENT,
                FeatureType.CHAT,
                FeatureType.CODE_COMPLETION,
                FeatureType.AUTO_APPLY,
                FeatureType.COMMIT_MESSAGE,
                FeatureType.INLINE_EDIT,
                FeatureType.NEXT_EDIT,
                FeatureType.LOOKUP
            )
        ),
        ServiceType.OPENAI to ModelCapability(
            ServiceType.OPENAI,
            setOf(
                FeatureType.AGENT,
                FeatureType.CHAT,
                FeatureType.CODE_COMPLETION,
                FeatureType.AUTO_APPLY,
                FeatureType.COMMIT_MESSAGE,
                FeatureType.INLINE_EDIT,
                FeatureType.LOOKUP
            )
        ),
        ServiceType.ANTHROPIC to ModelCapability(
            ServiceType.ANTHROPIC,
            setOf(
                FeatureType.AGENT,
                FeatureType.CHAT,
                FeatureType.AUTO_APPLY,
                FeatureType.COMMIT_MESSAGE,
                FeatureType.INLINE_EDIT,
                FeatureType.LOOKUP
            )
        ),
        ServiceType.GOOGLE to ModelCapability(
            ServiceType.GOOGLE,
            setOf(
                FeatureType.AGENT,
                FeatureType.CHAT,
                FeatureType.AUTO_APPLY,
                FeatureType.COMMIT_MESSAGE,
                FeatureType.INLINE_EDIT,
                FeatureType.LOOKUP
            )
        ),
        ServiceType.MISTRAL to ModelCapability(
            ServiceType.MISTRAL,
            setOf(
                FeatureType.AGENT,
                FeatureType.CHAT,
                FeatureType.CODE_COMPLETION,
                FeatureType.AUTO_APPLY,
                FeatureType.COMMIT_MESSAGE,
                FeatureType.INLINE_EDIT,
                FeatureType.LOOKUP
            )
        ),
        ServiceType.OLLAMA to ModelCapability(
            ServiceType.OLLAMA,
            setOf(
                FeatureType.AGENT,
                FeatureType.CHAT,
                FeatureType.CODE_COMPLETION,
                FeatureType.AUTO_APPLY,
                FeatureType.COMMIT_MESSAGE,
                FeatureType.INLINE_EDIT,
                FeatureType.LOOKUP
            )
        ),
        ServiceType.LLAMA_CPP to ModelCapability(
            ServiceType.LLAMA_CPP,
            setOf(
                FeatureType.CHAT, FeatureType.CODE_COMPLETION, FeatureType.AUTO_APPLY,
                FeatureType.COMMIT_MESSAGE, FeatureType.INLINE_EDIT, FeatureType.LOOKUP
            )
        ),
        ServiceType.CUSTOM_OPENAI to ModelCapability(
            ServiceType.CUSTOM_OPENAI,
            setOf(
                FeatureType.AGENT,
                FeatureType.CHAT,
                FeatureType.CODE_COMPLETION,
                FeatureType.AUTO_APPLY,
                FeatureType.COMMIT_MESSAGE,
                FeatureType.INLINE_EDIT,
                FeatureType.LOOKUP
            )
        ),
        ServiceType.INCEPTION to ModelCapability(
            ServiceType.INCEPTION,
            setOf(
                FeatureType.AGENT,
                FeatureType.CHAT,
                FeatureType.CODE_COMPLETION,
                FeatureType.AUTO_APPLY,
                FeatureType.COMMIT_MESSAGE,
                FeatureType.INLINE_EDIT,
                FeatureType.LOOKUP,
                FeatureType.NEXT_EDIT
            )
        )
    )

    private val pricingPlanBasedDefaults = mapOf(
        PricingPlan.ANONYMOUS to mapOf(
            FeatureType.AGENT to ModelSelection(
                ServiceType.PROXYAI,
                PROXYAI_AUTO,
                "Auto"
            ),
            FeatureType.CHAT to ModelSelection(
                ServiceType.PROXYAI,
                GEMINI_FLASH_2_5,
                "Gemini Flash 2.5"
            ),
            FeatureType.AUTO_APPLY to ModelSelection(
                ServiceType.PROXYAI,
                MERCURY_CODER,
                "Mercury Coder"
            ),
            FeatureType.COMMIT_MESSAGE to ModelSelection(
                ServiceType.PROXYAI,
                GPT_5_MINI,
                "GPT-5 Mini"
            ),
            FeatureType.INLINE_EDIT to ModelSelection(
                ServiceType.PROXYAI,
                GEMINI_FLASH_2_5,
                "Gemini Flash 2.5"
            ),
            FeatureType.LOOKUP to ModelSelection(
                ServiceType.PROXYAI,
                GPT_5_MINI,
                "GPT-5 Mini"
            ),
            FeatureType.CODE_COMPLETION to ModelSelection(
                ServiceType.PROXYAI,
                MERCURY_CODER,
                "Mercury Coder"
            ),
            FeatureType.NEXT_EDIT to ModelSelection(
                ServiceType.PROXYAI,
                MERCURY_CODER,
                "Mercury Coder"
            )
        ),
        PricingPlan.FREE to mapOf(
            FeatureType.AGENT to ModelSelection(
                ServiceType.PROXYAI,
                PROXYAI_AUTO,
                "Auto"
            ),
            FeatureType.CHAT to ModelSelection(ServiceType.PROXYAI, QWEN3_CODER, "Qwen3 Coder"),
            FeatureType.AUTO_APPLY to ModelSelection(
                ServiceType.PROXYAI,
                MERCURY_CODER,
                "Mercury Coder"
            ),
            FeatureType.COMMIT_MESSAGE to ModelSelection(
                ServiceType.PROXYAI,
                QWEN3_CODER,
                "Qwen3 Coder"
            ),
            FeatureType.INLINE_EDIT to ModelSelection(
                ServiceType.PROXYAI,
                QWEN3_CODER,
                "Qwen3 Coder"
            ),
            FeatureType.LOOKUP to ModelSelection(ServiceType.PROXYAI, QWEN3_CODER, "Qwen3 Coder"),
            FeatureType.CODE_COMPLETION to ModelSelection(
                ServiceType.PROXYAI,
                MERCURY_CODER,
                "Mercury Coder"
            ),
            FeatureType.NEXT_EDIT to ModelSelection(
                ServiceType.PROXYAI,
                MERCURY_CODER,
                "Mercury Coder"
            )
        ),
        PricingPlan.INDIVIDUAL to mapOf(
            FeatureType.AGENT to ModelSelection(
                ServiceType.PROXYAI,
                PROXYAI_AUTO,
                "Auto"
            ),
            FeatureType.CHAT to ModelSelection(
                ServiceType.PROXYAI,
                CLAUDE_4_5_SONNET_THINKING,
                "Claude 4.5 Sonnet Thinking"
            ),
            FeatureType.AUTO_APPLY to ModelSelection(
                ServiceType.PROXYAI,
                MERCURY_CODER,
                "Mercury Coder"
            ),
            FeatureType.COMMIT_MESSAGE to ModelSelection(
                ServiceType.PROXYAI,
                GPT_5_CODEX,
                "GPT-5 Codex"
            ),
            FeatureType.INLINE_EDIT to ModelSelection(
                ServiceType.PROXYAI,
                CLAUDE_4_5_SONNET,
                "Claude 4 Sonnet"
            ),
            FeatureType.LOOKUP to ModelSelection(ServiceType.PROXYAI, GPT_5_CODEX, "GPT-5 Codex"),
            FeatureType.CODE_COMPLETION to ModelSelection(
                ServiceType.PROXYAI,
                MERCURY_CODER,
                "Mercury Coder"
            ),
            FeatureType.NEXT_EDIT to ModelSelection(
                ServiceType.PROXYAI,
                MERCURY_CODER,
                "Mercury Coder"
            )
        )
    )

    private val fallbackDefaults = mapOf(
        FeatureType.AGENT to ModelSelection(
            ServiceType.PROXYAI,
            PROXYAI_AUTO,
            "Auto"
        ),
        FeatureType.CHAT to ModelSelection(
            ServiceType.PROXYAI,
            GEMINI_FLASH_2_5,
            "Gemini Flash 2.5"
        ),
        FeatureType.AUTO_APPLY to ModelSelection(
            ServiceType.PROXYAI,
            MERCURY_CODER,
            "Mercury Coder"
        ),
        FeatureType.COMMIT_MESSAGE to ModelSelection(
            ServiceType.PROXYAI,
            GPT_5_MINI,
            "GPT-5 Mini"
        ),
        FeatureType.INLINE_EDIT to ModelSelection(
            ServiceType.PROXYAI,
            GEMINI_FLASH_2_5,
            "Gemini Flash 2.5"
        ),
        FeatureType.LOOKUP to ModelSelection(ServiceType.PROXYAI, GPT_5_MINI, "GPT-5 Mini"),
        FeatureType.CODE_COMPLETION to ModelSelection(
            ServiceType.PROXYAI,
            MERCURY_CODER,
            "Mercury Coder"
        ),
        FeatureType.NEXT_EDIT to ModelSelection(ServiceType.PROXYAI, MERCURY_CODER, "Mercury Coder")
    )

    fun getAllModelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return when (featureType) {
            FeatureType.AGENT -> getAllAgentModels()
            FeatureType.CHAT, FeatureType.COMMIT_MESSAGE,
            FeatureType.INLINE_EDIT, FeatureType.LOOKUP -> getAllChatModels()

            FeatureType.AUTO_APPLY -> getAllApplyModels()
            FeatureType.CODE_COMPLETION -> getAllCodeModels()
            FeatureType.NEXT_EDIT -> getNextEditModels()
        }
    }

    fun getDefaultModelForFeature(
        featureType: FeatureType,
        pricingPlan: PricingPlan? = null
    ): ModelSelection {
        val planBasedDefaults = pricingPlan?.let { pricingPlanBasedDefaults[it] }
        return planBasedDefaults?.get(featureType) ?: fallbackDefaults[featureType]!!
    }

    fun getProvidersForFeature(featureType: FeatureType): List<ServiceType> {
        return providerCapabilities.values
            .filter { it.supportedFeatures.contains(featureType) }
            .map { it.provider }
    }

    fun isFeatureSupportedByProvider(featureType: FeatureType, provider: ServiceType): Boolean {
        return providerCapabilities[provider]?.supportedFeatures?.contains(featureType) == true
    }

    fun findModel(provider: ServiceType, modelCode: String): ModelSelection? {
        return getAllModels()
            .filter { it.provider == provider }
            .find { it.id == modelCode || it.model == modelCode }
    }

    fun getModelDisplayName(provider: ServiceType, modelCode: String?): String {
        val code = modelCode?.takeIf { it.isNotBlank() }
        return if (code != null) {
            findModel(provider, code)?.displayName ?: code
        } else {
            // Fallback to the first known model for the provider, or provider name
            getAllModels().firstOrNull { it.provider == provider }?.displayName ?: provider.name
        }
    }

    private fun getAllModels(): List<ModelSelection> {
        return buildList {
            addAll(getAllAgentModels())
            addAll(getAllApplyModels())
            addAll(getAllChatModels())
            addAll(getAllCodeModels())
            addAll(getNextEditModels())
        }.distinctBy { "${it.provider}:${it.model}" }
    }

    fun getAgentModels(serviceType: ServiceType?): List<LLMModelWrapper> {
        return getAgentModels()[serviceType] ?: emptyList()
    }

    data class LLMModelWrapper(val model: LLModel, val id: String = "", val name: String = "")

    private fun getProxyAIModelWrapper(id: String, name: String): LLMModelWrapper {
        return LLMModelWrapper(
            model = LLModel(
                id = id,
                provider = ProxyAILLMClient.ProxyAI,
                capabilities = listOf(
                    LLMCapability.Temperature,
                    LLMCapability.Schema.JSON.Basic,
                    LLMCapability.Schema.JSON.Standard,
                    LLMCapability.Speculation,
                    LLMCapability.Tools,
                    LLMCapability.ToolChoice,
                    LLMCapability.Vision.Image,
                    LLMCapability.Document,
                    LLMCapability.Completion,
                    LLMCapability.MultipleChoices,
                    LLMCapability.OpenAIEndpoint.Completions,
                ),
                contextLength = 200_000,
                maxOutputTokens = 32_768,
            ),
            name = name
        )
    }

    fun getAgentModels(): Map<ServiceType, List<LLMModelWrapper>> {
        return mapOf(
            ServiceType.PROXYAI to listOf(
                getProxyAIModelWrapper(PROXYAI_AUTO, "Auto"),
                getProxyAIModelWrapper(GPT5_2.id, "GPT-5.2"),
                getProxyAIModelWrapper(GPT5_2_Codex.id, "GPT-5.2 Codex"),
                getProxyAIModelWrapper(GPT5Mini.id, "GPT-5 Mini"),
                getProxyAIModelWrapper(Opus_4_5.id, "Claude Opus 4.5"),
                getProxyAIModelWrapper(Sonnet_4_5.id, "Claude Sonnet 4.5"),
                getProxyAIModelWrapper(Haiku_4_5.id, "Claude Haiku 4.5"),
            ),
            ServiceType.OPENAI to listOf(
                LLMModelWrapper(GPT5_2, name = "GPT-5.2"),
                LLMModelWrapper(GPT5_2_Codex, name = "GPT-5.2 Codex"),
                LLMModelWrapper(GPT5_1, name = "GPT-5.1"),
                LLMModelWrapper(GPT5_1Codex, name = "GPT-5.1 Codex"),
                LLMModelWrapper(GPT5Mini, name = "GPT-5 Mini"),
                LLMModelWrapper(GPT4_1, name = "GPT-4.1"),
                LLMModelWrapper(GPT4_1Mini, name = "GPT-4.1 Mini")
            ),
            ServiceType.ANTHROPIC to listOf(
                LLMModelWrapper(Opus_4_5, name = "Claude Opus 4.5"),
                LLMModelWrapper(Sonnet_4_5, name = "Claude Sonnet 4.5"),
                LLMModelWrapper(Haiku_4_5, name = "Claude Haiku 4.5")
            ),
            ServiceType.CUSTOM_OPENAI to getCustomOpenAIModels().map { model ->
                LLMModelWrapper(
                    model = LLModel(
                        id = model.model,
                        provider = CustomOpenAILLMClient.CustomOpenAI,
                        capabilities = listOf(
                            LLMCapability.Temperature,
                            LLMCapability.Schema.JSON.Basic,
                            LLMCapability.Schema.JSON.Standard,
                            LLMCapability.Speculation,
                            LLMCapability.Tools,
                            LLMCapability.ToolChoice,
                            LLMCapability.Vision.Image,
                            LLMCapability.Document,
                            LLMCapability.Completion,
                            LLMCapability.MultipleChoices,
                            LLMCapability.OpenAIEndpoint.Completions,
                        ),
                        contextLength = 200_000,
                        maxOutputTokens = 32_768,
                    ),
                    id = model.id ?: "",
                    name = model.displayName
                )
            },
            ServiceType.GOOGLE to listOf(
                LLMModelWrapper(Gemini3_Pro_Preview, name = "Gemini 3 Pro Preview"),
                LLMModelWrapper(Gemini3_Flash_Preview, name = "Gemini 3 Flash Preview"),
                LLMModelWrapper(Gemini2_5Pro, name = "Gemini 2.5 Pro"),
                LLMModelWrapper(Gemini2_5Flash, name = "Gemini 2.5 Flash")
            ),
            ServiceType.MISTRAL to listOf(
                LLMModelWrapper(Devstral2, name = "Devstral 2"),
                LLMModelWrapper(DevstralMedium, name = "Devstral Medium"),
            ),
            ServiceType.INCEPTION to listOf(
                LLMModelWrapper(model = Mercury, name = "Mercury"),
            ),
            ServiceType.OLLAMA to getOllamaModels().map { model ->
                LLMModelWrapper(
                    LLModel(
                        id = model.model,
                        provider = LLMProvider.Ollama,
                        capabilities = listOf(
                            LLMCapability.Temperature,
                            LLMCapability.Schema.JSON.Basic,
                            LLMCapability.Schema.JSON.Standard,
                            LLMCapability.Speculation,
                            LLMCapability.Tools,
                            LLMCapability.ToolChoice,
                            LLMCapability.Vision.Image,
                            LLMCapability.Document,
                            LLMCapability.Completion,
                            LLMCapability.MultipleChoices,
                            LLMCapability.OpenAIEndpoint.Completions,
                        ),
                        contextLength = 200_000,
                        maxOutputTokens = 32_768,
                    ),
                    name = model.displayName
                )
            }
        )
    }

    private fun getAllAgentModels(): List<ModelSelection> {
        return buildList {
            addAll(getProxyAIAgentModels())
            addAll(getAnthropicAgentModels())
            addAll(getOpenAIAgentModels())
            addAll(getCustomOpenAIModels())
            addAll(getGoogleAgentModels())
            addAll(getMistralAgentModels())
            addAll(getOllamaModels())
            addAll(getInceptionAgentModels())
        }
    }

    fun getOpenAIAgentModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(ServiceType.OPENAI, GPT5_2.id, "GPT-5.2", Icons.OpenAI),
            ModelSelection(ServiceType.OPENAI, GPT5_2_Codex.id, "GPT-5.2 Codex", Icons.OpenAI),
            ModelSelection(ServiceType.OPENAI, GPT5_1.id, "GPT-5.1", Icons.OpenAI),
            ModelSelection(ServiceType.OPENAI, GPT5_1Codex.id, "GPT-5.1 Codex", Icons.OpenAI),
            ModelSelection(ServiceType.OPENAI, GPT5Mini.id, "GPT-5 Mini", Icons.OpenAI),
        )
    }

    private fun getProxyAIAgentModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(ServiceType.PROXYAI, PROXYAI_AUTO, "Auto", Icons.DefaultSmall),
            ModelSelection(ServiceType.PROXYAI, GPT5_2.id, "GPT-5.2", Icons.OpenAI),
            ModelSelection(ServiceType.PROXYAI, GPT5_2_Codex.id, "GPT-5.2 Codex", Icons.OpenAI),
            ModelSelection(ServiceType.PROXYAI, GPT5Mini.id, "GPT-5 Mini", Icons.OpenAI),
            ModelSelection(ServiceType.PROXYAI, Opus_4_5.id, "Claude Opus 4.5", Icons.Anthropic),
            ModelSelection(
                ServiceType.PROXYAI,
                Sonnet_4_5.id,
                "Claude Sonnet 4.5",
                Icons.Anthropic
            ),
            ModelSelection(
                ServiceType.PROXYAI,
                Haiku_4_5.id,
                "Claude Haiku 4.5",
                Icons.Anthropic
            ),
        )
    }

    fun getAnthropicAgentModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(ServiceType.ANTHROPIC, Opus_4_5.id, "Claude Opus 4.5", Icons.Anthropic),
            ModelSelection(
                ServiceType.ANTHROPIC,
                Sonnet_4_5.id,
                "Claude Sonnet 4.5",
                Icons.Anthropic
            ),
            ModelSelection(
                ServiceType.ANTHROPIC,
                Haiku_4_5.id,
                "Claude Haiku 4.5",
                Icons.Anthropic
            ),
        )
    }

    fun getGoogleAgentModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(
                ServiceType.GOOGLE,
                Gemini3_Pro_Preview.id,
                "Gemini 3 Pro Preview",
                Icons.Google
            ),
            ModelSelection(
                ServiceType.GOOGLE,
                Gemini3_Flash_Preview.id,
                "Gemini 3 Flash Preview",
                Icons.Google
            ),
            ModelSelection(ServiceType.GOOGLE, Gemini2_5Pro.id, "Gemini 2.5 Pro", Icons.Google),
            ModelSelection(
                ServiceType.GOOGLE,
                Gemini2_5Flash.id,
                "Gemini 2.5 Flash",
                Icons.Google
            ),
        )
    }

    fun getMistralAgentModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(
                ServiceType.MISTRAL,
                Devstral2.id,
                "Devstral 2",
                Icons.Mistral
            ),
            ModelSelection(
                ServiceType.MISTRAL,
                DevstralMedium.id,
                "Devstral Medium",
                Icons.Mistral
            ),
        )
    }

    private fun getAllChatModels(): List<ModelSelection> {
        return buildList {
            addAll(getProxyAIChatModels())
            addAll(getOpenAIChatModels())
            addAll(getAnthropicModels())
            addAll(getGoogleModels())
            addAll(getMistralModels())
            addAll(getLlamaModels())
            addAll(getOllamaModels())
            addAll(getCustomOpenAIModels())
            addAll(getInceptionModels())
        }
    }

    private fun getAllApplyModels(): List<ModelSelection> {
        return buildList {
            addAll(getProxyAIApplyModels())
            addAll(getOpenAIChatModels())
            addAll(getAnthropicModels())
            addAll(getGoogleModels())
            addAll(getMistralModels())
            addAll(getLlamaModels())
            addAll(getOllamaModels())
            addAll(getCustomOpenAIModels())
            addAll(getInceptionModels())
        }
    }

    private fun getAllCodeModels(): List<ModelSelection> {
        return buildList {
            addAll(getProxyAICodeModels())
            add(getOpenAICodeModel())
            addAll(getMistralCodeModels())
            addAll(getLlamaModels())
            addAll(getCustomOpenAICodeModels())
            addAll(getOllamaModels())
            addAll(getInceptionModels())
        }
    }

    private fun getCustomOpenAICodeModels(): List<ModelSelection> {
        return try {
            val customServicesSettings = service<CustomServicesSettings>()
            customServicesSettings.state.services.mapNotNull { service ->
                val serviceId = service.id ?: return@mapNotNull null
                val serviceName = service.name ?: ""
                val modelFromBody = service.codeCompletionSettings.body["model"]
                val modelName = (modelFromBody as? String)
                val displayName = if (!modelName.isNullOrEmpty()) {
                    if (modelName.length > 20) "$serviceName (...${modelName.takeLast(20)})" else "$serviceName ($modelName)"
                } else serviceName

                ModelSelection(ServiceType.CUSTOM_OPENAI, serviceId, displayName)
            }
        } catch (e: Exception) {
            logger.error("Failed to get Custom OpenAI code models", e)
            emptyList()
        }
    }

    private fun getNextEditModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(ServiceType.PROXYAI, MERCURY_CODER, "Mercury Coder"),
            ModelSelection(ServiceType.INCEPTION, MERCURY_CODER, "Mercury Coder")
        )
    }

    private fun getInceptionModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(ServiceType.INCEPTION, MERCURY_CODER, "Mercury Coder")
        )
    }

    fun getInceptionAgentModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(ServiceType.INCEPTION, MERCURY, "Mercury"),
        )
    }

    fun getProxyAIChatModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(
                ServiceType.PROXYAI,
                GPT_5_CODEX,
                "GPT-5 Codex",
                Icons.OpenAI,
                PricingPlan.INDIVIDUAL
            ),
            ModelSelection(
                ServiceType.PROXYAI,
                GPT_5_MINI,
                "GPT-5 Mini",
                Icons.OpenAI,
                PricingPlan.ANONYMOUS
            ),
            ModelSelection(
                ServiceType.PROXYAI,
                CLAUDE_4_5_SONNET_THINKING,
                "Claude Sonnet 4.5 (thinking)",
                Icons.Anthropic,
                PricingPlan.INDIVIDUAL
            ),
            ModelSelection(
                ServiceType.PROXYAI,
                CLAUDE_4_5_SONNET,
                "Claude Sonnet 4.5",
                Icons.Anthropic,
                PricingPlan.INDIVIDUAL
            ),
            ModelSelection(
                ServiceType.PROXYAI,
                GEMINI_PRO_2_5,
                "Gemini 3.0 Pro Preview",
                Icons.Google,
                PricingPlan.INDIVIDUAL
            ),
            ModelSelection(
                ServiceType.PROXYAI,
                GEMINI_FLASH_2_5,
                "Gemini 2.5 Flash",
                Icons.Google,
                PricingPlan.ANONYMOUS
            ),
            ModelSelection(
                ServiceType.PROXYAI,
                DEEPSEEK_R1,
                "DeepSeek R1",
                Icons.DeepSeek,
                PricingPlan.INDIVIDUAL
            ),
            ModelSelection(
                ServiceType.PROXYAI,
                DEEPSEEK_V3,
                "DeepSeek V3",
                Icons.DeepSeek,
                PricingPlan.FREE
            ),
            ModelSelection(
                ServiceType.PROXYAI,
                QWEN3_CODER,
                "Qwen3 Coder",
                Icons.Qwen,
                PricingPlan.FREE
            )
        )
    }

    fun getProxyAIApplyModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(
                ServiceType.PROXYAI,
                MERCURY_CODER,
                "Mercury Coder",
                Icons.Inception,
                PricingPlan.FREE
            ),
            ModelSelection(ServiceType.PROXYAI, MORPH, "Morph", Icons.Morph, PricingPlan.FREE),
            ModelSelection(ServiceType.PROXYAI, RELACE, "Relace", Icons.Relace, PricingPlan.FREE),
        )
    }

    fun getProxyAIChatModelsForPricingPlan(userPricingPlan: PricingPlan?): List<ModelSelection> {
        val allModels = getProxyAIChatModels()
        return when (userPricingPlan) {
            null, PricingPlan.ANONYMOUS -> allModels.filter {
                it.pricingPlan == PricingPlan.ANONYMOUS || it.pricingPlan == PricingPlan.FREE || it.pricingPlan == PricingPlan.INDIVIDUAL
            }

            PricingPlan.FREE -> allModels.filter {
                it.pricingPlan != PricingPlan.INDIVIDUAL
            }

            PricingPlan.INDIVIDUAL -> allModels
        }
    }

    private fun getProxyAICodeModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(
                ServiceType.PROXYAI,
                MERCURY_CODER,
                "Mercury Coder",
                Icons.Inception,
                PricingPlan.FREE
            ),
        )
    }

    private fun getOpenAIChatModels(): List<ModelSelection> {
        val openAIModels = listOf(
            GPT_5,
            GPT_5_MINI,
            O4_MINI,
            O3_PRO,
            O3,
            O3_MINI,
            GPT_4_1,
            GPT_4_1_MINI,
            GPT_4_1_NANO,
            O1_PREVIEW,
            O1_MINI,
            GPT_5_MINI,
            GPT_4O,
            GPT_4_0125_PREVIEW,
            GPT_3_5_TURBO_INSTRUCT,
            GPT_4_VISION_PREVIEW,
            GPT_5_CODEX,
        )

        return openAIModels.mapNotNull { modelId ->
            OpenAIChatCompletionModel.entries.find { it.code == modelId }?.let { model ->
                ModelSelection(ServiceType.OPENAI, model.code, model.description)
            }
        }
    }

    private fun getOpenAICodeModel(): ModelSelection {
        return ModelSelection(ServiceType.OPENAI, GPT_3_5_TURBO_INSTRUCT, "GPT-3.5 Turbo Instruct")
    }

    private fun getAnthropicModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(ServiceType.ANTHROPIC, CLAUDE_OPUS_4_20250514, "Claude Opus 4"),
            ModelSelection(ServiceType.ANTHROPIC, CLAUDE_SONNET_4_20250514, "Claude Sonnet 4")
        )
    }

    private fun getGoogleModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(
                ServiceType.GOOGLE,
                GoogleModel.GEMINI_2_5_PRO_PREVIEW.code,
                GoogleModel.GEMINI_2_5_PRO_PREVIEW.description
            ),
            ModelSelection(
                ServiceType.GOOGLE,
                GoogleModel.GEMINI_2_5_FLASH_PREVIEW.code,
                GoogleModel.GEMINI_2_5_FLASH_PREVIEW.description
            ),
            ModelSelection(
                ServiceType.GOOGLE,
                GoogleModel.GEMINI_2_5_PRO.code,
                GoogleModel.GEMINI_2_5_PRO.description
            ),
            ModelSelection(
                ServiceType.GOOGLE,
                GoogleModel.GEMINI_2_0_PRO_EXP.code,
                GoogleModel.GEMINI_2_0_PRO_EXP.description
            ),
            ModelSelection(
                ServiceType.GOOGLE,
                GoogleModel.GEMINI_2_0_FLASH_THINKING_EXP.code,
                GoogleModel.GEMINI_2_0_FLASH_THINKING_EXP.description
            ),
            ModelSelection(
                ServiceType.GOOGLE,
                GoogleModel.GEMINI_2_0_FLASH.code,
                GoogleModel.GEMINI_2_0_FLASH.description
            ),
            ModelSelection(
                ServiceType.GOOGLE,
                GoogleModel.GEMINI_1_5_PRO.code,
                GoogleModel.GEMINI_1_5_PRO.description
            )
        )
    }

    private fun getMistralModels(): List<ModelSelection> {
        return listOf(
            ModelSelection(ServiceType.MISTRAL, Devstral2.id, "Devstral 2"),
            ModelSelection(ServiceType.MISTRAL, DEVSTRAL_MEDIUM_2507, "Devstral Medium"),
            ModelSelection(ServiceType.MISTRAL, MISTRAL_LARGE_2411, "Mistral Large"),
            ModelSelection(ServiceType.MISTRAL, CODESTRAL_LATEST, "Codestral"),
        )
    }

    private fun getMistralCodeModels(): List<ModelSelection> {
        return listOf(ModelSelection(ServiceType.MISTRAL, CODESTRAL_LATEST, "Codestral"))
    }

    private fun getOllamaModels(): List<ModelSelection> {
        return try {
            val ollamaSettings = service<OllamaSettings>()
            ollamaSettings.state.availableModels.map { model ->
                ModelSelection(ServiceType.OLLAMA, model, model)
            }
        } catch (e: Exception) {
            logger.error("Failed to get Ollama models", e)
            emptyList()
        }
    }

    fun getCustomOpenAIModels(): List<ModelSelection> {
        return try {
            service<CustomServicesSettings>().state.services.mapNotNull { service ->
                val serviceId = service.id ?: return@mapNotNull null
                val serviceName = service.name ?: ""
                val model = service.chatCompletionSettings.body["model"] as? String ?: ""
                val displayName = if (model.isNotEmpty()) {
                    if (model.length > 20) "$serviceName (...${model.takeLast(20)})" else "$serviceName ($model)"
                } else serviceName

                ModelSelection(
                    id = serviceId,
                    provider = ServiceType.CUSTOM_OPENAI,
                    model = model,
                    displayName = displayName
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to get Custom OpenAI models", e)
            emptyList()
        }
    }

    private fun getLlamaModels(): List<ModelSelection> {
        return try {
            LlamaModel.entries.flatMap { llamaModel ->
                llamaModel.huggingFaceModels.map { hfModel ->
                    val displayName =
                        "${llamaModel.label} (${hfModel.parameterSize}B) / Q${hfModel.quantization}"
                    ModelSelection(ServiceType.LLAMA_CPP, hfModel.name, displayName)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to get llama.cpp models", e)
            emptyList()
        }
    }

    companion object {
        // ProxyAI Models
        const val PROXYAI_AUTO = "auto"
        const val GEMINI_PRO_2_5 = "gemini-pro-2.5"
        const val GEMINI_FLASH_2_5 = "gemini-flash-2.5"
        const val CLAUDE_4_SONNET = "claude-4-sonnet"
        const val CLAUDE_4_SONNET_THINKING = "claude-4-sonnet-thinking"
        const val CLAUDE_4_5_SONNET = "claude-sonnet-4-5"
        const val CLAUDE_4_5_SONNET_THINKING = "claude-sonnet-4-5-thinking"
        const val DEEPSEEK_R1 = "deepseek-r1"
        const val DEEPSEEK_V3 = "deepseek-v3"
        const val QWEN_2_5_32B_CODE = "qwen-2.5-32b-code"
        const val QWEN3_CODER = "qwen3-coder"
        const val RELACE = "relace"
        const val MORPH = "morph"

        // OpenAI Models
        const val GPT_3_5_TURBO_INSTRUCT = "gpt-3.5-turbo-instruct"
        const val O4_MINI = "o4-mini"
        const val O3_PRO = "o3-pro"
        const val O3 = "o3"
        const val O3_MINI = "o3-mini"
        const val O1_PREVIEW = "o1-preview"
        const val O1_MINI = "o1-mini"
        const val GPT_4_1 = "gpt-4.1"
        const val GPT_4_1_MINI = "gpt-4.1-mini"
        const val GPT_4_1_NANO = "gpt-4.1-nano"
        const val GPT_4O = "gpt-4o"
        const val GPT_4O_MINI = "gpt-4o-mini"
        const val GPT_4_0125_PREVIEW = "gpt-4-0125-preview"
        const val GPT_4_VISION_PREVIEW = "gpt-4-vision-preview"
        const val GPT_5 = "gpt-5"
        const val GPT_5_MINI = "gpt-5-mini"
        const val GPT_5_CODEX = "gpt-5-codex"
        const val GPT_5_1_CODEX = "gpt-5.1-codex"

        // Anthropic Models
        const val CLAUDE_OPUS_4_20250514 = "claude-opus-4-20250514"
        const val CLAUDE_SONNET_4_20250514 = "claude-sonnet-4-20250514"

        // Google Models
        const val GEMINI_2_0_FLASH = "gemini-2.0-flash"

        // Mistral Models
        const val MISTRAL_LARGE_2411 = "mistral-large-2411"
        const val DEVSTRAL_MEDIUM_2507 = "devstral-medium-2507"
        const val CODESTRAL_LATEST = "codestral-latest"

        // Ollama default models
        const val LLAMA_3_2 = "llama3.2"

        // Llama.cpp default models
        const val LLAMA_3_2_3B_INSTRUCT = "llama-3.2-3b-instruct"

        const val MERCURY = "mercury"
        const val MERCURY_CODER = "mercury-coder"

        @JvmStatic
        fun getInstance(): ModelRegistry {
            return ApplicationManager.getApplication().getService(ModelRegistry::class.java)
        }
    }
}

public val Gemini3_Flash_Preview: LLModel = LLModel(
    provider = LLMProvider.Google,
    id = "gemini-3-flash-preview",
    capabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Completion,
        LLMCapability.MultipleChoices,
        LLMCapability.Vision.Image,
        LLMCapability.Vision.Video,
        LLMCapability.Audio,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Schema.JSON.Standard,
    ),
    contextLength = 1_048_576,
    maxOutputTokens = 65_536,
)


public val GPT5_2_Codex: LLModel = LLModel(
    provider = LLMProvider.OpenAI,
    id = "gpt-5.2-codex",
    capabilities = listOf(
        LLMCapability.Completion,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Schema.JSON.Standard,
        LLMCapability.Speculation,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Vision.Image,
        LLMCapability.Document,
        LLMCapability.MultipleChoices,
        LLMCapability.OpenAIEndpoint.Completions,
        LLMCapability.OpenAIEndpoint.Responses,
    ),
    contextLength = 400_000,
    maxOutputTokens = 128_000,
)

public val Devstral2: LLModel = LLModel(
    provider = LLMProvider.MistralAI,
    id = "devstral-2512",
    capabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Completion,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Schema.JSON.Standard
    ),
    contextLength = 128_000
)

public val Mercury: LLModel = LLModel(
    id = MERCURY,
    provider = InceptionAILLMClient.Inception,
    capabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Schema.JSON.Standard,
        LLMCapability.Speculation,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Vision.Image,
        LLMCapability.Document,
        LLMCapability.Completion,
        LLMCapability.MultipleChoices,
        LLMCapability.OpenAIEndpoint.Completions,
    ),
    contextLength = 200_000,
    maxOutputTokens = 32_768,
)
