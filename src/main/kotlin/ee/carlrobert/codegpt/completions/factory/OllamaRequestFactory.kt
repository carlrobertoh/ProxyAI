package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.BaseRequestFactory
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.completions.factory.OpenAIRequestFactory.Companion.buildOpenAIMessages
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionRequest
import ee.carlrobert.llm.completion.CompletionRequest

class OllamaRequestFactory : BaseRequestFactory() {

    override fun createChatRequest(params: ChatCompletionParameters): CompletionRequest {
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
            .setMaxCompletionTokens(configuration.maxTokens)
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
    ): CompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(featureType)
        return OpenAIRequestFactory.createBasicCompletionRequest(
            systemPrompt,
            userPrompt,
            model = model,
            isStream = stream
        )
    }
}
