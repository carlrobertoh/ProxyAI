package ee.carlrobert.codegpt.settings.service

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.settings.models.ModelRegistry
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.models.ModelSettingsState
import ee.carlrobert.llm.client.codegpt.PricingPlan
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest

class ModelSelectionServiceTest : IntegrationTest() {

    private lateinit var modelSelectionService: ModelSelectionService
    private lateinit var modelSettings: ModelSettings

    override fun setUp() {
        super.setUp()
        modelSelectionService = service<ModelSelectionService>()
        modelSettings = service<ModelSettings>()

        val cleanState = ModelSettingsState()
        cleanState.modelSelections.clear()
        modelSettings.loadState(cleanState)
    }

    fun `test getModelSelectionForFeature with valid feature returns model`() {
        val result = modelSelectionService.getModelSelectionForFeature(FeatureType.CHAT)
        val expected = ModelRegistry.getInstance().getDefaultModelForFeature(FeatureType.CHAT)

        assertThat(result.provider).isEqualTo(expected.provider)
        assertThat(result.model).isEqualTo(expected.model)
        assertThat(result.displayName).isEqualTo(expected.displayName)
    }

    fun `test getModelSelectionForFeature with pricing plan returns plan-specific model`() {
        val individualResult = modelSelectionService.getModelSelectionForFeature(
            FeatureType.CHAT,
            PricingPlan.INDIVIDUAL
        )
        val freeResult =
            modelSelectionService.getModelSelectionForFeature(FeatureType.CHAT, PricingPlan.FREE)

        assertThat(individualResult.model).isEqualTo("claude-sonnet-4-5-thinking")
        assertThat(freeResult.model).isEqualTo("qwen3-coder")
    }

    fun `test getModelSelectionForFeature with code completion returns code model`() {
        val result = modelSelectionService.getModelSelectionForFeature(FeatureType.CODE_COMPLETION)

        assertThat(result.provider).isEqualTo(ServiceType.PROXYAI)
        assertThat(result.model).isEqualTo("mercury-coder")
    }

    fun `test getServiceForFeature with valid feature returns correct provider`() {
        val result = modelSelectionService.getServiceForFeature(FeatureType.CHAT)

        assertThat(result).isEqualTo(ServiceType.PROXYAI)
    }

    fun `test getServiceForFeature with pricing plan returns plan-specific provider`() {
        val result =
            modelSelectionService.getServiceForFeature(FeatureType.CHAT, PricingPlan.INDIVIDUAL)

        assertThat(result).isEqualTo(ServiceType.PROXYAI)
    }

    fun `test getModelForFeature with valid feature returns model string`() {
        val result = modelSelectionService.getModelForFeature(FeatureType.CHAT)
        val expected = ModelRegistry.getInstance().getDefaultModelForFeature(FeatureType.CHAT)

        assertThat(result).isEqualTo(expected.model)
    }

    fun `test getModelForFeature with pricing plan returns plan-specific model`() {
        val result =
            modelSelectionService.getModelForFeature(FeatureType.CHAT, PricingPlan.INDIVIDUAL)

        assertThat(result).isEqualTo("claude-sonnet-4-5-thinking")
    }
}