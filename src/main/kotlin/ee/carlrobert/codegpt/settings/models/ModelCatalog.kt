package ee.carlrobert.codegpt.settings.models

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels.Haiku_4_5
import ai.koog.prompt.llm.LLModel
import com.intellij.openapi.components.Service
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import javax.swing.Icon

data class ModelSelection(
    val provider: ServiceType,
    val llmModel: LLModel,
    val displayName: String,
    val icon: Icon? = null,
    val serviceId: String? = null,
) {
    val modelId: String
        get() = llmModel.id

    val selectionId: String
        get() = serviceId ?: modelId

    val model: String
        get() = selectionId

    val id: String?
        get() = serviceId

    val fullDisplayName: String = if (provider == ServiceType.LLAMA_CPP) {
        displayName
    } else {
        "$provider • $displayName"
    }
}

@Service
class ModelCatalog {

    private val providerRegistry = ModelProviderRegistry()

    private data class DefaultModelRef(val provider: ServiceType, val selectionId: String)

    private val defaultModelRefs = mapOf(
        FeatureType.AGENT to DefaultModelRef(ServiceType.PROXYAI, PROXYAI_AUTO),
        FeatureType.CHAT to DefaultModelRef(ServiceType.PROXYAI, PROXYAI_AUTO),
        FeatureType.AUTO_APPLY to DefaultModelRef(ServiceType.PROXYAI, MERCURY_CODER),
        FeatureType.COMMIT_MESSAGE to DefaultModelRef(ServiceType.PROXYAI, Haiku_4_5.id),
        FeatureType.INLINE_EDIT to DefaultModelRef(ServiceType.PROXYAI, Haiku_4_5.id),
        FeatureType.LOOKUP to DefaultModelRef(ServiceType.PROXYAI, Haiku_4_5.id),
        FeatureType.CODE_COMPLETION to DefaultModelRef(ServiceType.PROXYAI, MERCURY_CODER),
        FeatureType.NEXT_EDIT to DefaultModelRef(ServiceType.PROXYAI, MERCURY_CODER),
    )

    fun getAllModelsForFeature(featureType: FeatureType): List<ModelSelection> {
        return providerRegistry.modelsForFeature(featureType)
    }

    fun getDefaultModelForFeature(featureType: FeatureType): ModelSelection {
        val ref = defaultModelRefs.getValue(featureType)
        return providerRegistry.findModel(ref.provider, ref.selectionId)
            ?: error("Missing default model mapping for feature=$featureType provider=${ref.provider} id=${ref.selectionId}")
    }

    fun getProvidersForFeature(featureType: FeatureType): List<ServiceType> {
        return providerRegistry.providersForFeature(featureType)
    }

    fun isFeatureSupportedByProvider(featureType: FeatureType, provider: ServiceType): Boolean {
        return providerRegistry.isFeatureSupported(featureType, provider)
    }

    fun findModel(provider: ServiceType, modelCode: String): ModelSelection? {
        return providerRegistry.findModel(provider, modelCode)
    }

    fun getModelDisplayName(provider: ServiceType, modelCode: String?): String {
        val code = modelCode?.takeIf { it.isNotBlank() }
        return if (code != null) {
            findModel(provider, code)?.displayName ?: code
        } else {
            getAllModels().firstOrNull { it.provider == provider }?.displayName ?: provider.name
        }
    }

    fun getAllModels(): List<ModelSelection> {
        return providerRegistry.allModels()
    }

    companion object {
        // ProxyAI Models
        const val PROXYAI_AUTO = "auto"
        const val GEMINI_FLASH_2_5 = "gemini-flash-2.5"
        const val CLAUDE_4_SONNET = "claude-4-sonnet"
        const val CLAUDE_4_SONNET_THINKING = "claude-4-sonnet-thinking"
        const val CLAUDE_4_5_SONNET = "claude-sonnet-4-5"
        const val CLAUDE_4_5_SONNET_THINKING = "claude-sonnet-4-5-thinking"

        // OpenAI Models
        const val GPT_3_5_TURBO_INSTRUCT = "gpt-3.5-turbo-instruct"
        const val GPT_5_MINI = "gpt-5-mini"

        // Mistral Models
        const val CODESTRAL_LATEST = "codestral-latest"

        // Ollama default models
        const val LLAMA_3_2 = "llama3.2"

        const val MERCURY_CODER = "mercury-coder"
    }
}
