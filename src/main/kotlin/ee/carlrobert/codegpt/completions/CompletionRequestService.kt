package ee.carlrobert.codegpt.completions

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ee.carlrobert.codegpt.agent.AgentFactory
import ee.carlrobert.codegpt.agent.clients.CustomOpenAILLMClient
import ee.carlrobert.codegpt.agent.clients.HttpClientProvider
import ee.carlrobert.codegpt.agent.clients.RetryingPromptExecutor
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import kotlinx.coroutines.runBlocking
import javax.swing.JPanel
import kotlin.time.Duration.Companion.seconds

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
        serviceType: ServiceType,
        prompt: Prompt,
        modelSelection: ModelSelection,
        callParameters: ChatCompletionParameters,
        eventListener: ChatStreamEventListener,
        onToolCallUIUpdate: ((JPanel) -> Unit)? = null
    ): CancellableRequest {
        val request = CompletionRunnerRequest.Chat(
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
        val client = CustomOpenAILLMClient.fromSettingsState(
            apiKey.orEmpty(),
            settings,
            HttpClientProvider.createHttpClient()
        )
        val retryPolicy = RetryingPromptExecutor.RetryPolicy(
            maxAttempts = 2,
            initialDelay = 1.seconds,
            maxDelay = 4.seconds,
            backoffMultiplier = 2.0,
            jitterFactor = 0.1
        )
        val request = CompletionRunnerRequest.Streaming(
            executor = RetryingPromptExecutor.fromClient(client, retryPolicy, null),
            model = LLModel(
                id = modelId?.takeIf { it.isNotBlank() } ?: "gpt-4.1-mini",
                provider = CustomOpenAILLMClient.CustomOpenAI,
                capabilities = emptyList(),
                contextLength = 128_000,
                maxOutputTokens = 4_096
            ),
            prompt = prompt("custom-service-test-connection") {
                user("Test connection")
            },
            eventListener = eventListener,
            mode = StreamingMode.SINGLE_RESPONSE,
            cancellationResultBuilder = { StringBuilder() }
        )
        return CompletionRunnerFactory.create(request).run(request)
    }
}
