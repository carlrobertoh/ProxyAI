package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.BaseRequestFactory
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.completions.factory.OpenAIRequestFactory.Companion.buildOpenAIMessages
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionRequest

class MistralRequestFactory : BaseRequestFactory() {

    override fun createChatRequest(params: ChatCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.CHAT)
        val configuration = service<ConfigurationSettings>().state

        return OpenAIChatCompletionRequest.Builder(
            buildOpenAIMessages(
                model = model,
                callParameters = params,
                referencedFiles = params.referencedFiles,
                conversationsHistory = params.history,
                psiStructure = params.psiStructure,
            )
        )
            .setModel(model)
            .setMaxTokens(configuration.maxTokens)
            .setMaxCompletionTokens(null)
            .setStream(true)
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
        return OpenAIRequestFactory.createBasicCompletionRequest(
            systemPrompt,
            userPrompt,
            model = model,
            isStream = stream
        )
    }
}