package ee.carlrobert.codegpt.settings.models

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Haiku_4_5
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Opus_4_5
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Sonnet_4_5
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.google.GoogleModels.Gemini3_Pro_Preview
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT5Mini
import ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT5_2
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.agent.clients.CustomOpenAILLMClient
import ee.carlrobert.codegpt.agent.clients.InceptionAILLMClient
import ee.carlrobert.codegpt.agent.clients.ProxyAILLMClient
import ee.carlrobert.codegpt.completions.llama.LlamaModel
import ee.carlrobert.codegpt.settings.models.ModelCatalog.Companion.MERCURY_CODER
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import javax.swing.Icon

interface ModelProvider {
    val serviceType: ServiceType
    val supportedFeatures: Set<FeatureType>
    fun modelsForFeature(featureType: FeatureType): List<ModelSelection>
}

class ModelProviderRegistry(
    private val providers: List<ModelProvider> = defaultModelProviders()
) {

    fun allProviders(): List<ModelProvider> = providers

    fun provider(serviceType: ServiceType): ModelProvider? =
        providers.firstOrNull { it.serviceType == serviceType }

    fun requireProvider(serviceType: ServiceType): ModelProvider {
        return provider(serviceType)
            ?: throw IllegalArgumentException("No ModelProvider for $serviceType")
    }

    fun providersForFeature(featureType: FeatureType): List<ServiceType> {
        return providers.filter { featureType in it.supportedFeatures }.map { it.serviceType }
    }

    fun isFeatureSupported(featureType: FeatureType, serviceType: ServiceType): Boolean {
        return featureType in requireProvider(serviceType).supportedFeatures
    }

    fun modelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return providers
            .asSequence()
            .filter { featureType in it.supportedFeatures }
            .flatMap { it.modelsForFeature(featureType).asSequence() }
            .distinctBy { "${it.provider}:${it.selectionId}" }
            .toList()
    }

    fun allModels(): List<ModelSelection> {
        return providers
            .asSequence()
            .flatMap { provider ->
                FeatureType.entries.asSequence()
                    .filter { it in provider.supportedFeatures }
                    .flatMap { provider.modelsForFeature(it).asSequence() }
            }
            .distinctBy { "${it.provider}:${it.selectionId}" }
            .toList()
    }

    fun findModel(serviceType: ServiceType, modelCode: String): ModelSelection? {
        return allModels()
            .asSequence()
            .filter { it.provider == serviceType }
            .firstOrNull { it.selectionId == modelCode || it.modelId == modelCode }
    }
}

private val CHAT_FEATURES = setOf(
    FeatureType.CHAT,
    FeatureType.COMMIT_MESSAGE,
    FeatureType.INLINE_EDIT,
    FeatureType.LOOKUP,
)

private fun defaultModelProviders(): List<ModelProvider> {
    return listOf(
        ProxyAIModelProvider(),
        OpenAIModelProvider(),
        AnthropicModelProvider(),
        GoogleModelProvider(),
        MistralModelProvider(),
        LlamaCppModelProvider(),
        OllamaModelProvider(),
        CustomOpenAIModelProvider(),
        InceptionModelProvider(),
    )
}

private val OPENAI_CAPABILITIES = listOf(
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
)

private fun virtualModel(
    provider: LLMProvider,
    modelId: String,
    capabilities: List<LLMCapability>,
    contextLength: Long = 200_000,
    maxOutputTokens: Long? = 32_768,
): LLModel {
    return LLModel(
        id = modelId,
        provider = provider,
        capabilities = capabilities,
        contextLength = contextLength,
        maxOutputTokens = maxOutputTokens,
    )
}

private fun formatCustomModelDisplayName(serviceName: String, modelName: String): String {
    val safeServiceName = serviceName.trim().ifBlank { ServiceType.CUSTOM_OPENAI.label }
    val safeModelName = modelName.trim()

    return if (safeModelName.length > 20) {
        "$safeServiceName (...${safeModelName.takeLast(20)})"
    } else {
        "$safeServiceName ($safeModelName)"
    }
}

private fun defaultModelDisplayName(modelId: String): String {
    return when (modelId) {
        // ProxyAI
        "auto" -> "Auto"

        // OpenAI
        GPT5_4.id -> "GPT-5.4"
        GPT5_3_Codex.id -> "GPT-5.3 Codex"
        OpenAIModels.Chat.GPT5_2Pro.id -> "GPT-5.2 Pro"
        GPT5_2_Codex.id -> "GPT-5.2 Codex"
        GPT5_2.id -> "GPT-5.2"
        OpenAIModels.Chat.GPT5_1Codex.id -> "GPT-5.1 Codex"
        OpenAIModels.Chat.GPT5_1.id -> "GPT-5.1"
        OpenAIModels.Chat.GPT5Codex.id -> "GPT-5 Codex"
        GPT5Mini.id -> "GPT-5 Mini"
        OpenAIModels.Chat.GPT4_1.id -> "GPT-4.1"
        OpenAIModels.Chat.GPT4_1Mini.id -> "GPT-4.1 Mini"
        ModelCatalog.GPT_3_5_TURBO_INSTRUCT -> "GPT-3.5 Turbo Instruct"

        // Anthropic
        Opus_4_6.id -> "Claude Opus 4.6"
        Opus_4_5.id -> "Claude Opus 4.5"
        Sonnet_4_6.id -> "Claude Sonnet 4.6"
        Sonnet_4_5.id -> "Claude Sonnet 4.5"
        Haiku_4_5.id -> "Claude Haiku 4.5"

        // Google
        Gemini3_1_Pro_Preview.id -> "Gemini 3.1 Pro Preview"
        Gemini3_Pro_Preview.id -> "Gemini 3 Pro Preview"
        Gemini3_Flash_Preview.id -> "Gemini 3 Flash Preview"
        GoogleModels.Gemini2_5Pro.id -> "Gemini 2.5 Pro"
        GoogleModels.Gemini2_5Flash.id -> "Gemini 2.5 Flash"
        GoogleModels.Gemini2_5FlashLite.id -> "Gemini 2.5 Flash Lite"

        // Mistral
        Devstral2.id -> "Devstral 2"
        MistralAIModels.Chat.DevstralMedium.id -> "Devstral Medium"
        MistralAIModels.Chat.Codestral.id -> "Codestral"

        // Inception
        Mercury.id -> "Mercury"
        MERCURY_CODER -> "Mercury Coder"

        else -> modelId
    }
}

private val Gemini3_Flash_Preview: LLModel = LLModel(
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

private val Gemini3_1_Pro_Preview: LLModel = LLModel(
    provider = LLMProvider.Google,
    id = "gemini-3.1-pro-preview",
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

val Sonnet_4_6: LLModel = LLModel(
    provider = LLMProvider.Anthropic,
    id = "claude-sonnet-4-6",
    capabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Vision.Image,
        LLMCapability.Document,
        LLMCapability.Completion
    ),
    contextLength = 200_000,
    maxOutputTokens = 64_000,
)

val Opus_4_6: LLModel = LLModel(
    provider = LLMProvider.Anthropic,
    id = "claude-opus-4-6",
    capabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Vision.Image,
        LLMCapability.Document,
        LLMCapability.Completion
    ),
    contextLength = 200_000,
    maxOutputTokens = 64_000,
)


private val GPT5_4: LLModel = LLModel(
    provider = LLMProvider.OpenAI,
    id = "gpt-5.4",
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
        LLMCapability.OpenAIEndpoint.Responses,
    ),
    contextLength = 1_050_000,
    maxOutputTokens = 128_000,
)

private val GPT5_3_Codex: LLModel = LLModel(
    provider = LLMProvider.OpenAI,
    id = "gpt-5.3-codex",
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
        LLMCapability.OpenAIEndpoint.Responses,
    ),
    contextLength = 400_000,
    maxOutputTokens = 128_000,
)

private val GPT5_2_Codex: LLModel = LLModel(
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
        LLMCapability.OpenAIEndpoint.Responses,
    ),
    contextLength = 400_000,
    maxOutputTokens = 128_000,
)

val Devstral2: LLModel = LLModel(
    provider = LLMProvider.MistralAI,
    id = "devstral-2512",
    capabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Completion,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Schema.JSON.Basic,
        LLMCapability.Schema.JSON.Standard,
    ),
    contextLength = 256_000,
    maxOutputTokens = 65_536,
)

val Mercury: LLModel = LLModel(
    provider = InceptionAILLMClient.Inception,
    id = "mercury",
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

private class ProxyAIModelProvider : ModelProvider {
    override val serviceType: ServiceType = ServiceType.PROXYAI
    override val supportedFeatures: Set<FeatureType> = setOf(
        FeatureType.AGENT,
        FeatureType.CHAT,
        FeatureType.CODE_COMPLETION,
        FeatureType.AUTO_APPLY,
        FeatureType.COMMIT_MESSAGE,
        FeatureType.INLINE_EDIT,
        FeatureType.NEXT_EDIT,
        FeatureType.LOOKUP,
    )

    override fun modelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return when (featureType) {
            in (CHAT_FEATURES + FeatureType.AGENT) -> listOf(
                defaultModel(ModelCatalog.PROXYAI_AUTO, Icons.DefaultSmall),
                defaultModel(GPT5_4.id, Icons.OpenAI),
                defaultModel(GPT5_3_Codex.id, Icons.OpenAI),
                defaultModel(GPT5Mini.id, Icons.OpenAI),
                defaultModel(Opus_4_6.id, Icons.Anthropic),
                defaultModel(Sonnet_4_6.id, Icons.Anthropic),
                defaultModel(Haiku_4_5.id, Icons.Anthropic),
                defaultModel(Gemini3_1_Pro_Preview.id, Icons.Google),
                defaultModel(Gemini3_Flash_Preview.id, Icons.Google),
            )

            FeatureType.AUTO_APPLY -> listOf(
                defaultModel(MERCURY_CODER, Icons.Inception),
            )

            FeatureType.CODE_COMPLETION -> listOf(
                defaultModel(MERCURY_CODER, Icons.Inception)
            )

            FeatureType.NEXT_EDIT -> listOf(
                defaultModel(MERCURY_CODER)
            )

            else -> emptyList()
        }
    }

    private fun defaultModel(
        modelId: String,
        icon: Icon? = null,
    ): ModelSelection {
        return ModelSelection(
            provider = serviceType,
            llmModel = virtualModel(ProxyAILLMClient.ProxyAI, modelId, OPENAI_CAPABILITIES),
            displayName = defaultModelDisplayName(modelId),
            icon = icon,
        )
    }
}

private class OpenAIModelProvider : ModelProvider {
    override val serviceType: ServiceType = ServiceType.OPENAI
    override val supportedFeatures: Set<FeatureType> = setOf(
        FeatureType.AGENT,
        FeatureType.CHAT,
        FeatureType.CODE_COMPLETION,
        FeatureType.AUTO_APPLY,
        FeatureType.COMMIT_MESSAGE,
        FeatureType.INLINE_EDIT,
        FeatureType.LOOKUP,
    )

    private val models = listOf(
        GPT5_4,
        GPT5_3_Codex,
        GPT5_2_Codex,
        OpenAIModels.Chat.GPT5_2Pro,
        GPT5_2,
        OpenAIModels.Chat.GPT5_1Codex,
        OpenAIModels.Chat.GPT5_1,
        OpenAIModels.Chat.GPT5Codex,
        GPT5Mini,
        OpenAIModels.Chat.GPT4_1,
        OpenAIModels.Chat.GPT4_1Mini,
    )

    override fun modelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return when (featureType) {
            in CHAT_FEATURES, FeatureType.AGENT, FeatureType.AUTO_APPLY ->
                models.map { defaultModel(it) }

            FeatureType.CODE_COMPLETION -> listOf(
                defaultModel(
                    virtualModel(
                        LLMProvider.OpenAI,
                        ModelCatalog.GPT_3_5_TURBO_INSTRUCT,
                        OPENAI_CAPABILITIES
                    )
                )
            )

            else -> emptyList()
        }
    }

    private fun defaultModel(llmModel: LLModel): ModelSelection {
        return ModelSelection(
            provider = serviceType,
            llmModel = llmModel,
            displayName = defaultModelDisplayName(llmModel.id),
        )
    }
}

private class AnthropicModelProvider : ModelProvider {
    override val serviceType: ServiceType = ServiceType.ANTHROPIC
    override val supportedFeatures: Set<FeatureType> = setOf(
        FeatureType.AGENT,
        FeatureType.CHAT,
        FeatureType.AUTO_APPLY,
        FeatureType.COMMIT_MESSAGE,
        FeatureType.INLINE_EDIT,
        FeatureType.LOOKUP,
    )

    private val models = listOf(
        Opus_4_6,
        Opus_4_5,
        Sonnet_4_6,
        Sonnet_4_5,
        Haiku_4_5,
    )

    override fun modelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return when (featureType) {
            FeatureType.AGENT, in CHAT_FEATURES, FeatureType.AUTO_APPLY -> models.map { model ->
                ModelSelection(
                    provider = serviceType,
                    llmModel = model,
                    displayName = defaultModelDisplayName(model.id),
                )
            }

            else -> emptyList()
        }
    }
}

private class GoogleModelProvider : ModelProvider {
    override val serviceType: ServiceType = ServiceType.GOOGLE
    override val supportedFeatures: Set<FeatureType> = setOf(
        FeatureType.AGENT,
        FeatureType.CHAT,
        FeatureType.AUTO_APPLY,
        FeatureType.COMMIT_MESSAGE,
        FeatureType.INLINE_EDIT,
        FeatureType.LOOKUP,
    )

    private val chatModels = listOf(
        Gemini3_1_Pro_Preview,
        Gemini3_Pro_Preview,
        Gemini3_Flash_Preview,
    )

    override fun modelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return when (featureType) {
            FeatureType.AGENT -> listOf(
                defaultModel(Gemini3_1_Pro_Preview),
                defaultModel(Gemini3_Pro_Preview),
                defaultModel(Gemini3_Flash_Preview),
            )

            in CHAT_FEATURES, FeatureType.AUTO_APPLY -> chatModels.map { model ->
                ModelSelection(
                    provider = serviceType,
                    llmModel = model,
                    displayName = defaultModelDisplayName(model.id),
                )
            }

            else -> emptyList()
        }
    }

    private fun defaultModel(llmModel: LLModel): ModelSelection {
        return ModelSelection(
            provider = serviceType,
            llmModel = llmModel,
            displayName = defaultModelDisplayName(llmModel.id),
        )
    }
}

private class MistralModelProvider : ModelProvider {
    override val serviceType: ServiceType = ServiceType.MISTRAL
    override val supportedFeatures: Set<FeatureType> = setOf(
        FeatureType.AGENT,
        FeatureType.CHAT,
        FeatureType.CODE_COMPLETION,
        FeatureType.AUTO_APPLY,
        FeatureType.COMMIT_MESSAGE,
        FeatureType.INLINE_EDIT,
        FeatureType.LOOKUP,
    )

    private val chatModels = listOf(
        Devstral2,
        MistralAIModels.Chat.DevstralMedium,
        MistralAIModels.Chat.Codestral,
    )

    override fun modelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return when (featureType) {
            FeatureType.AGENT -> listOf(
                defaultModel(Devstral2),
                defaultModel(MistralAIModels.Chat.DevstralMedium),
            )

            in CHAT_FEATURES, FeatureType.AUTO_APPLY -> chatModels.map { defaultModel(it) }

            FeatureType.CODE_COMPLETION -> listOf(defaultModel(MistralAIModels.Chat.Codestral))

            else -> emptyList()
        }
    }

    private fun defaultModel(llmModel: LLModel): ModelSelection {
        return ModelSelection(
            provider = serviceType,
            llmModel = llmModel,
            displayName = defaultModelDisplayName(llmModel.id),
        )
    }
}

private class InceptionModelProvider : ModelProvider {
    override val serviceType: ServiceType = ServiceType.INCEPTION
    override val supportedFeatures: Set<FeatureType> = setOf(
        FeatureType.AGENT,
        FeatureType.CHAT,
        FeatureType.CODE_COMPLETION,
        FeatureType.AUTO_APPLY,
        FeatureType.COMMIT_MESSAGE,
        FeatureType.INLINE_EDIT,
        FeatureType.LOOKUP,
        FeatureType.NEXT_EDIT,
    )

    override fun modelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return when (featureType) {
            FeatureType.AGENT -> listOf(inceptionMercury())
            in CHAT_FEATURES, FeatureType.AUTO_APPLY -> listOf(
                inceptionMercury(),
                inceptionMercuryCoder(),
            )

            FeatureType.CODE_COMPLETION -> listOf(inceptionMercuryCoder())
            FeatureType.NEXT_EDIT -> listOf(inceptionMercuryCoder())
            else -> emptyList()
        }
    }

    private fun inceptionMercury(): ModelSelection {
        return ModelSelection(
            provider = serviceType,
            llmModel = Mercury,
            displayName = defaultModelDisplayName(Mercury.id),
        )
    }

    private fun inceptionMercuryCoder(): ModelSelection {
        return ModelSelection(
            provider = serviceType,
            llmModel = virtualModel(
                InceptionAILLMClient.Inception,
                MERCURY_CODER,
                OPENAI_CAPABILITIES
            ),
            displayName = "Mercury Coder",
        )
    }
}

private class LlamaCppModelProvider : ModelProvider {
    override val serviceType: ServiceType = ServiceType.LLAMA_CPP
    override val supportedFeatures: Set<FeatureType> = setOf(
        FeatureType.CHAT,
        FeatureType.CODE_COMPLETION,
        FeatureType.AUTO_APPLY,
        FeatureType.COMMIT_MESSAGE,
        FeatureType.INLINE_EDIT,
        FeatureType.LOOKUP,
    )

    override fun modelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return LlamaModel.entries.flatMap { llamaModel ->
            llamaModel.huggingFaceModels.map { hfModel ->
                val displayName =
                    "${llamaModel.label} (${hfModel.parameterSize}B) / Q${hfModel.quantization}"
                ModelSelection(
                    provider = serviceType,
                    llmModel = virtualModel(
                        LLMProvider.OpenAI,
                        hfModel.name,
                        OPENAI_CAPABILITIES
                    ),
                    displayName = displayName,
                )
            }
        }
    }
}

private class OllamaModelProvider : ModelProvider {
    override val serviceType: ServiceType = ServiceType.OLLAMA
    override val supportedFeatures: Set<FeatureType> = setOf(
        FeatureType.AGENT,
        FeatureType.CHAT,
        FeatureType.CODE_COMPLETION,
        FeatureType.AUTO_APPLY,
        FeatureType.COMMIT_MESSAGE,
        FeatureType.INLINE_EDIT,
        FeatureType.LOOKUP,
    )

    private fun availableModels(): List<String> {
        return runCatching { service<OllamaSettings>().state.availableModels }.getOrDefault(
            emptyList()
        )
    }

    override fun modelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return availableModels().map { model ->
            ModelSelection(
                provider = serviceType,
                llmModel = virtualModel(LLMProvider.Ollama, model, OPENAI_CAPABILITIES),
                displayName = model,
            )
        }
    }
}

private class CustomOpenAIModelProvider : ModelProvider {
    override val serviceType: ServiceType = ServiceType.CUSTOM_OPENAI
    override val supportedFeatures: Set<FeatureType> = setOf(
        FeatureType.AGENT,
        FeatureType.CHAT,
        FeatureType.CODE_COMPLETION,
        FeatureType.AUTO_APPLY,
        FeatureType.COMMIT_MESSAGE,
        FeatureType.INLINE_EDIT,
        FeatureType.LOOKUP,
    )

    private fun buildModels(
        modelExtractor: (CustomServiceSettingsState) -> String?
    ): List<ModelSelection> {
        return runCatching {
            service<CustomServicesSettings>().state.services.mapNotNull { svc ->
                val serviceId = svc.id ?: return@mapNotNull null
                val serviceName = svc.name ?: ""
                val modelName =
                    modelExtractor(svc)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val displayName = formatCustomModelDisplayName(serviceName, modelName)

                ModelSelection(
                    provider = serviceType,
                    llmModel = virtualModel(
                        CustomOpenAILLMClient.CustomOpenAI,
                        modelName,
                        OPENAI_CAPABILITIES
                    ),
                    displayName = displayName,
                    serviceId = serviceId,
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun modelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return when (featureType) {
            FeatureType.CODE_COMPLETION -> buildModels { svc ->
                svc.codeCompletionSettings.body["model"] as? String
            }

            else -> buildModels { svc ->
                svc.chatCompletionSettings.body["model"] as? String
            }
        }
    }
}
