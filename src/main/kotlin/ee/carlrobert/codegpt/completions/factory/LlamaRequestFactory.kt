package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.BaseRequestFactory
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.completions.InlineEditCompletionParameters
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionRequest
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionStandardMessage

class LlamaRequestFactory : BaseRequestFactory() {

    override fun createChatRequest(params: ChatCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.CHAT)
        val configuration = service<ConfigurationSettings>().state
        return OpenAIChatCompletionRequest.Builder(
            OpenAIRequestFactory.buildOpenAIMessages(model, params)
        )
            .setModel(model)
            .setStream(true)
            .setMaxTokens(null)
            .setMaxCompletionTokens(configuration.maxTokens)
            .setTemperature(configuration.temperature.toDouble())
            .build()
    }

    override fun createBasicCompletionRequest(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        stream: Boolean,
        featureType: FeatureType
    ): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(featureType)
        val configuration = service<ConfigurationSettings>().state
        return OpenAIChatCompletionRequest.Builder(
            listOf(
                OpenAIChatCompletionStandardMessage("system", systemPrompt),
                OpenAIChatCompletionStandardMessage("user", userPrompt)
            )
        )
            .setModel(model)
            .setStream(stream)
            .setMaxTokens(null)
            .setMaxCompletionTokens(maxTokens)
            .setTemperature(configuration.temperature.toDouble())
            .build()
    }

    override fun createInlineEditRequest(params: InlineEditCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.INLINE_EDIT)
        val prepared = prepareInlineEditPrompts(params)
        val messages = OpenAIRequestFactory.buildInlineEditMessages(prepared, params.conversation)
        val configuration = service<ConfigurationSettings>().state
        return OpenAIChatCompletionRequest.Builder(messages)
            .setModel(model)
            .setStream(true)
            .setTemperature(configuration.temperature.toDouble())
            .build()
    }
}
