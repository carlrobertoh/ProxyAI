package ee.carlrobert.codegpt.settings.models

import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.llm.client.codegpt.PricingPlan
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest

class ModelRegistryTest : IntegrationTest() {

    private lateinit var modelRegistry: ModelRegistry

    override fun setUp() {
        super.setUp()
        modelRegistry = ModelRegistry.getInstance()
    }

    fun `test getDefaultModelForFeature with individual plan returns premium model`() {
        val result = modelRegistry.getDefaultModelForFeature(FeatureType.CHAT, PricingPlan.INDIVIDUAL)

        assertThat(result.provider).isEqualTo(ServiceType.PROXYAI)
        assertThat(result.model).isEqualTo("claude-sonnet-4-5-thinking")
        assertThat(result.displayName).isEqualTo("Claude 4.5 Sonnet Thinking")
    }

    fun `test getDefaultModelForFeature with free plan returns free model`() {
        val result = modelRegistry.getDefaultModelForFeature(FeatureType.CHAT, PricingPlan.FREE)

        assertThat(result.provider).isEqualTo(ServiceType.PROXYAI)
        assertThat(result.model).isEqualTo("qwen3-coder")
        assertThat(result.displayName).isEqualTo("Qwen3 Coder")
    }

    fun `test getDefaultModelForFeature with anonymous plan returns basic model`() {
        val result = modelRegistry.getDefaultModelForFeature(FeatureType.CHAT, PricingPlan.ANONYMOUS)

        assertThat(result.provider).isEqualTo(ServiceType.PROXYAI)
        assertThat(result.model).isEqualTo("gemini-flash-2.5")
        assertThat(result.displayName).isEqualTo("Gemini Flash 2.5")
    }

    fun `test getDefaultModelForFeature with null plan returns fallback model`() {
        val result = modelRegistry.getDefaultModelForFeature(FeatureType.CHAT, null)

        assertThat(result.provider).isEqualTo(ServiceType.PROXYAI)
        assertThat(result.model).isEqualTo("gemini-flash-2.5")
        assertThat(result.displayName).isEqualTo("Gemini Flash 2.5")
    }

    fun `test getDefaultModelForFeature with code completion returns code model`() {
        val result = modelRegistry.getDefaultModelForFeature(FeatureType.CODE_COMPLETION, PricingPlan.INDIVIDUAL)

        assertThat(result.provider).isEqualTo(ServiceType.PROXYAI)
        assertThat(result.model).isEqualTo("mercury-coder")
        assertThat(result.displayName).isEqualTo("Mercury Coder")
    }

    fun `test getDefaultModelForFeature with next edit returns zeta model`() {
        val result = modelRegistry.getDefaultModelForFeature(FeatureType.NEXT_EDIT, PricingPlan.INDIVIDUAL)

        assertThat(result.provider).isEqualTo(ServiceType.PROXYAI)
        assertThat(result.model).isEqualTo("mercury-coder")
        assertThat(result.displayName).isEqualTo("Mercury Coder")
    }

    fun `test getAllModelsForFeature with chat returns chat models only`() {
        val result = modelRegistry.getAllModelsForFeature(FeatureType.CHAT)

        assertThat(result).anyMatch { it.provider == ServiceType.PROXYAI && it.model == "claude-sonnet-4-5-thinking" }
        assertThat(result).anyMatch { it.provider == ServiceType.OPENAI && it.model == "gpt-4.1" }
        assertThat(result).anyMatch { it.provider == ServiceType.ANTHROPIC && it.model == "claude-sonnet-4-20250514" }
        assertThat(result).anyMatch { it.provider == ServiceType.GOOGLE && it.model.contains("gemini") }
        assertThat(result).anyMatch { it.provider == ServiceType.MISTRAL && it.model == "codestral-latest" }
        assertThat(result).noneMatch { it.provider == ServiceType.ANTHROPIC && it.model == "qwen-2.5-32b-code" }
    }

    fun `test getAllModelsForFeature with code completion returns code models only`() {
        val result = modelRegistry.getAllModelsForFeature(FeatureType.CODE_COMPLETION)

        assertThat(result).anyMatch { it.provider == ServiceType.PROXYAI && it.model == "mercury-coder" }
        assertThat(result).anyMatch { it.provider == ServiceType.OPENAI && it.model == "gpt-3.5-turbo-instruct" }
        assertThat(result).anyMatch { it.provider == ServiceType.MISTRAL && it.model == "codestral-latest" }
        assertThat(result).noneMatch { it.provider == ServiceType.ANTHROPIC }
        assertThat(result).noneMatch { it.provider == ServiceType.GOOGLE }
    }

    fun `test getAllModelsForFeature with next edit returns next edit models only`() {
        val result = modelRegistry.getAllModelsForFeature(FeatureType.NEXT_EDIT)

        assertThat(result.first().provider).isEqualTo(ServiceType.PROXYAI)
        assertThat(result.first().model).isEqualTo("mercury-coder")
    }

    fun `test getProvidersForFeature with chat returns all chat providers`() {
        val result = modelRegistry.getProvidersForFeature(FeatureType.CHAT)

        assertThat(result).containsExactlyInAnyOrder(
            ServiceType.PROXYAI,
            ServiceType.OPENAI,
            ServiceType.ANTHROPIC,
            ServiceType.GOOGLE,
            ServiceType.MISTRAL,
            ServiceType.OLLAMA,
            ServiceType.LLAMA_CPP,
            ServiceType.CUSTOM_OPENAI,
            ServiceType.INCEPTION,
        )
    }

    fun `test getProvidersForFeature with code completion returns code providers only`() {
        val result = modelRegistry.getProvidersForFeature(FeatureType.CODE_COMPLETION)

        assertThat(result).containsExactlyInAnyOrder(
            ServiceType.PROXYAI,
            ServiceType.OPENAI,
            ServiceType.MISTRAL,
            ServiceType.OLLAMA,
            ServiceType.LLAMA_CPP,
            ServiceType.CUSTOM_OPENAI,
            ServiceType.INCEPTION
        )
        assertThat(result).doesNotContain(ServiceType.ANTHROPIC, ServiceType.GOOGLE)
    }

    fun `test getProvidersForFeature with next edit returns proxyai and inception`() {
        val result = modelRegistry.getProvidersForFeature(FeatureType.NEXT_EDIT)

        assertThat(result).containsExactlyInAnyOrder(ServiceType.PROXYAI, ServiceType.INCEPTION)
    }

    fun `test isFeatureSupportedByProvider with valid chat provider returns true`() {
        val result = modelRegistry.isFeatureSupportedByProvider(FeatureType.CHAT, ServiceType.OPENAI)

        assertThat(result).isTrue
    }

    fun `test isFeatureSupportedByProvider with invalid code provider returns false`() {
        val result = modelRegistry.isFeatureSupportedByProvider(FeatureType.CODE_COMPLETION, ServiceType.ANTHROPIC)

        assertThat(result).isFalse
    }

    fun `test isFeatureSupportedByProvider with next edit non-proxyai returns false`() {
        val result = modelRegistry.isFeatureSupportedByProvider(FeatureType.NEXT_EDIT, ServiceType.OPENAI)

        assertThat(result).isFalse
    }

    fun `test findModel with existing provider and model returns model selection`() {
        val result = modelRegistry.findModel(ServiceType.PROXYAI, "gemini-flash-2.5")

        assertThat(result).isNotNull
        assertThat(result!!.provider).isEqualTo(ServiceType.PROXYAI)
        assertThat(result.model).isEqualTo("gemini-flash-2.5")
        assertThat(result.displayName).isEqualTo("Gemini 2.5 Flash")
    }

    fun `test findModel with existing anthropic model returns model selection`() {
        val result = modelRegistry.findModel(ServiceType.ANTHROPIC, "claude-sonnet-4-20250514")

        assertThat(result).isNotNull
        assertThat(result!!.provider).isEqualTo(ServiceType.ANTHROPIC)
        assertThat(result.model).isEqualTo("claude-sonnet-4-20250514")
        assertThat(result.displayName).isEqualTo("Claude Sonnet 4")
    }

    fun `test findModel with existing mistral model returns model selection`() {
        val result = modelRegistry.findModel(ServiceType.MISTRAL, "codestral-latest")

        assertThat(result).isNotNull
        assertThat(result!!.provider).isEqualTo(ServiceType.MISTRAL)
        assertThat(result.model).isEqualTo("codestral-latest")
        assertThat(result.displayName).isEqualTo("Codestral")
    }

    fun `test findModel with non-existing model returns null`() {
        val result = modelRegistry.findModel(ServiceType.OPENAI, "non-existing-model")

        assertThat(result).isNull()
    }

    fun `test findModel with wrong provider for model returns null`() {
        val result = modelRegistry.findModel(ServiceType.ANTHROPIC, "gpt-4o")

        assertThat(result).isNull()
    }
}
