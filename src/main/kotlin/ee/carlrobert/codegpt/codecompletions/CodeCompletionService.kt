package ee.carlrobert.codegpt.codecompletions

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.codecompletions.CodeCompletionRequestFactory.buildCustomRequest
import ee.carlrobert.codegpt.codecompletions.CodeCompletionRequestFactory.buildLlamaRequest
import ee.carlrobert.codegpt.codecompletions.CodeCompletionRequestFactory.buildOllamaRequest
import ee.carlrobert.codegpt.codecompletions.CodeCompletionRequestFactory.buildOpenAIRequest
import ee.carlrobert.codegpt.completions.CompletionClientProvider
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.ServiceType.*
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.mistral.MistralSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionEventSourceListener
import ee.carlrobert.llm.client.openai.completion.OpenAITextCompletionEventSourceListener
import ee.carlrobert.llm.completion.CompletionEventListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSources.createFactory

@Service(Service.Level.PROJECT)
class CodeCompletionService(private val project: Project) {

    fun getSelectedModelCode(): String? {
        return ModelSelectionService.getInstance().getModelForFeature(FeatureType.CODE_COMPLETION)
    }

    fun isCodeCompletionsEnabled(): Boolean = isCodeCompletionsEnabled(
        ModelSelectionService.getInstance().getServiceForFeature(FeatureType.CODE_COMPLETION)
    )

    fun isCodeCompletionsEnabled(selectedService: ServiceType): Boolean =
        when (selectedService) {
            PROXYAI -> service<CodeGPTServiceSettings>().state.codeCompletionSettings.codeCompletionsEnabled
            OPENAI -> OpenAISettings.getCurrentState().isCodeCompletionsEnabled
            CUSTOM_OPENAI -> service<CustomServicesSettings>().state.active.codeCompletionSettings.codeCompletionsEnabled
            MISTRAL -> MistralSettings.getCurrentState().isCodeCompletionsEnabled
            LLAMA_CPP -> LlamaSettings.isCodeCompletionsPossible()
            OLLAMA -> service<OllamaSettings>().state.codeCompletionsEnabled
            else -> false
        }

    fun getCodeCompletionAsync(
        infillRequest: InfillRequest,
        eventListener: CompletionEventListener<String>
    ): EventSource {
        return when (val selectedService =
            ModelSelectionService.getInstance().getServiceForFeature(FeatureType.CODE_COMPLETION)) {
            OPENAI -> CompletionClientProvider.getOpenAIClient()
                .getCompletionAsync(buildOpenAIRequest(infillRequest), eventListener)

            CUSTOM_OPENAI -> createFactory(
                CompletionClientProvider.getDefaultClientBuilder().build()
            ).newEventSource(
                buildCustomRequest(infillRequest),
                if (service<CustomServicesSettings>().state.active.codeCompletionSettings.parseResponseAsChatCompletions) {
                    OpenAIChatCompletionEventSourceListener(eventListener)
                } else {
                    OpenAITextCompletionEventSourceListener(eventListener)
                }
            )

            MISTRAL -> CompletionClientProvider.getMistralClient()
                .getCodeCompletionAsync(buildOpenAIRequest(infillRequest), eventListener)

            OLLAMA -> CompletionClientProvider.getOllamaClient()
                .getCompletionAsync(buildOllamaRequest(infillRequest), eventListener)

            LLAMA_CPP -> CompletionClientProvider.getLlamaClient()
                .getChatCompletionAsync(buildLlamaRequest(infillRequest), eventListener)

            else -> throw IllegalArgumentException("Code completion not supported for ${selectedService.name}")
        }
    }
}
