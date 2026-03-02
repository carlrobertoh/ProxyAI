package ee.carlrobert.codegpt.codecompletions

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.agent.clients.CodeCompletionCapable
import ee.carlrobert.codegpt.agent.clients.CustomOpenAILLMClient
import ee.carlrobert.codegpt.agent.clients.toCompletionError
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.codegpt.completions.CancellableRequest
import ee.carlrobert.codegpt.completions.CompletionStreamEventListener
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.models.LLMClientFactory
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.ServiceType.*
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceCodeCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.inception.InceptionSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.mistral.MistralSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Service
class CodeCompletionService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getSelectedModelCode(): String {
        return ModelSettings.getInstance().getModelForFeature(FeatureType.CODE_COMPLETION)
    }

    fun isCodeCompletionsEnabled(): Boolean = isCodeCompletionsEnabled(
        ModelSettings.getInstance().getServiceForFeature(FeatureType.CODE_COMPLETION)
    )

    fun isCodeCompletionsEnabled(selectedService: ServiceType): Boolean =
        when (selectedService) {
            PROXYAI -> service<CodeGPTServiceSettings>().state.codeCompletionSettings.codeCompletionsEnabled
            OPENAI -> OpenAISettings.getCurrentState().isCodeCompletionsEnabled
            CUSTOM_OPENAI -> service<CustomServicesSettings>()
                .customServiceStateForFeatureType(FeatureType.CODE_COMPLETION)
                .codeCompletionSettings
                .codeCompletionsEnabled

            MISTRAL -> MistralSettings.getCurrentState().isCodeCompletionsEnabled
            LLAMA_CPP -> LlamaSettings.isCodeCompletionsPossible()
            OLLAMA -> service<OllamaSettings>().state.codeCompletionsEnabled
            INCEPTION -> service<InceptionSettings>().state.codeCompletionsEnabled
            else -> false
        }

    fun getCodeCompletionAsync(
        infillRequest: InfillRequest,
        eventListener: CompletionStreamEventListener
    ): CancellableRequest {
        val selectedService = ModelSettings.getInstance().getServiceForFeature(FeatureType.CODE_COMPLETION)
        return when (selectedService) {
            OPENAI, CUSTOM_OPENAI, MISTRAL, OLLAMA, LLAMA_CPP, INCEPTION ->
                executeCodeCompletion(selectedService, infillRequest, eventListener)

            PROXYAI -> executeProxyAICodeCompletion(infillRequest, eventListener)
            ANTHROPIC, GOOGLE -> throw IllegalArgumentException("Code completion not supported for $selectedService")
        }
    }

    fun testCustomOpenAIConnectionAsync(
        settings: CustomServiceCodeCompletionSettingsState,
        apiKey: String?,
        eventListener: CompletionStreamEventListener
    ): CancellableRequest {
        return executeWithClient(
            clientProvider = {
                CustomOpenAILLMClient.fromCodeCompletionSettingsState(
                    apiKey.orEmpty(),
                    settings
                )
            },
            infillRequest = InfillRequest.Builder("Hello", "!", 0).build(),
            eventListener = eventListener
        )
    }

    private fun executeCodeCompletion(
        serviceType: ServiceType,
        infillRequest: InfillRequest,
        eventListener: CompletionStreamEventListener
    ): CancellableRequest {
        return executeWithClient(
            clientProvider = {
                val client = LLMClientFactory.createClient(serviceType, FeatureType.CODE_COMPLETION)
                client as? CodeCompletionCapable
                    ?: error("Code completion not supported by ${client::class.simpleName}")
            },
            infillRequest = infillRequest,
            eventListener = eventListener
        )
    }

    private fun executeProxyAICodeCompletion(
        infillRequest: InfillRequest,
        eventListener: CompletionStreamEventListener
    ): CancellableRequest {
        val project = infillRequest.editor?.project
            ?: throw IllegalArgumentException("ProxyAI code completion requires an active editor project")
        return project.service<GrpcClientService>()
            .getCodeCompletionAsync(infillRequest, eventListener)
    }

    private fun executeWithClient(
        clientProvider: () -> CodeCompletionCapable,
        infillRequest: InfillRequest,
        eventListener: CompletionStreamEventListener
    ): CancellableRequest {
        val job = scope.launch {
            val messageBuilder = StringBuilder()
            val client = clientProvider()
            try {
                val completion = client.getCodeCompletion(infillRequest)
                eventListener.onOpen()
                if (completion.isNotEmpty()) {
                    messageBuilder.append(completion)
                    eventListener.onMessage(completion)
                }
                eventListener.onComplete(messageBuilder)
            } catch (_: CancellationException) {
                eventListener.onCancelled(messageBuilder)
            } catch (ex: Throwable) {
                eventListener.onError(ex.toCompletionError(), ex)
            } finally {
                runCatching { client.close() }
            }
        }

        return CancellableRequest { job.cancel() }
    }
}
