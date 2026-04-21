package ee.carlrobert.codegpt.completions

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.AgentFactory
import ee.carlrobert.codegpt.agent.clients.CustomOpenAILLMClient
import ee.carlrobert.codegpt.agent.clients.HttpClientProvider
import ee.carlrobert.codegpt.completions.factory.ResponsesApiUtil
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.swing.JPanel

object CompletionRequestService {

    fun getCompletion(
        serviceType: ServiceType,
        featureType: FeatureType,
        prompt: Prompt,
        modelSelection: ModelSelection,
        tools: List<ToolDescriptor> = emptyList()
    ): String {
        val executor = AgentFactory.createExecutor(serviceType, featureType = featureType)
        return try {
            runBlocking {
                val responses = executor.execute(prompt, modelSelection.llmModel, tools)
                CompletionTextExtractor.extract(responses)
            }
        } finally {
            runCatching { executor.close() }
        }
    }

    fun getCompletionAsync(
        serviceType: ServiceType,
        featureType: FeatureType,
        prompt: Prompt,
        modelSelection: ModelSelection,
        eventListener: CompletionStreamEventListener,
        tools: List<ToolDescriptor> = emptyList()
    ): CancellableRequest {
        val request = CompletionRunnerRequest.Streaming(
            executor = AgentFactory.createExecutor(serviceType, featureType = featureType),
            model = modelSelection.llmModel,
            prompt = prompt,
            eventListener = eventListener,
            tools = tools,
            mode = StreamingMode.STREAMING,
            cancellationResultBuilder = { StringBuilder(it) }
        )
        return CompletionRunnerFactory.create(request).run(request)
    }

    @JvmStatic
    fun getChatCompletionAsync(
        project: Project,
        serviceType: ServiceType,
        prompt: Prompt,
        modelSelection: ModelSelection,
        callParameters: ChatCompletionParameters,
        eventListener: ChatStreamEventListener,
        onToolCallUIUpdate: ((JPanel) -> Unit)? = null
    ): CancellableRequest {
        val request = CompletionRunnerRequest.Chat(
            project = project,
            serviceType = serviceType,
            executor = AgentFactory.createExecutor(serviceType, featureType = FeatureType.CHAT),
            model = modelSelection.llmModel,
            prompt = prompt,
            callParameters = callParameters,
            eventListener = eventListener,
            onToolCallUIUpdate = onToolCallUIUpdate ?: {}
        )
        return CompletionRunnerFactory.create(request).run(request)
    }

    fun testCustomServiceConnectionAsync(
        settings: CustomServiceChatCompletionSettingsState,
        apiKey: String?,
        modelId: String?,
        eventListener: CompletionStreamEventListener
    ): CancellableRequest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val model = LLModel(
            id = modelId?.takeIf { it.isNotBlank() } ?: "gpt-4.1-mini",
            provider = CustomOpenAILLMClient.CustomOpenAI,
            capabilities = listOf(
                if (ResponsesApiUtil.isResponsesApiUrl(settings.url)) {
                    LLMCapability.OpenAIEndpoint.Responses
                } else {
                    LLMCapability.OpenAIEndpoint.Completions
                }
            ),
            contextLength = 128_000,
            maxOutputTokens = 4_096
        )
        val testPrompt = prompt("custom-service-test-connection") {
            user("Test connection")
        }

        val job = scope.launch {
            val client = CustomOpenAILLMClient.fromSettingsState(
                apiKey.orEmpty(),
                settings,
                HttpClientProvider.createHttpClient()
            )
            val messageBuilder = StringBuilder()
            eventListener.onOpen()
            try {
                val responses = client.execute(testPrompt, model, emptyList())
                val text = CompletionTextExtractor.extract(responses)
                if (text.isNotBlank()) {
                    messageBuilder.append(text)
                    eventListener.onMessage(text)
                }
                eventListener.onComplete(StringBuilder(messageBuilder))
            } catch (_: CancellationException) {
                eventListener.onCancelled(StringBuilder(messageBuilder))
            } catch (exception: Throwable) {
                eventListener.onError(
                    CompletionError(exception.message ?: "Failed to complete request"),
                    exception
                )
            } finally {
                runCatching { client.close() }
                scope.cancel()
            }
        }

        return CancellableRequest { job.cancel() }
    }
}
