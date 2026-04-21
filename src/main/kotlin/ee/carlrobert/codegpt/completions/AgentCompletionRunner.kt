package ee.carlrobert.codegpt.completions

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.tokenizer.feature.MessageTokenizer
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.tokenizer.Tokenizer
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.agent.AgentEvents
import ee.carlrobert.codegpt.agent.MessageWithContext
import ee.carlrobert.codegpt.agent.clients.shouldStream
import ee.carlrobert.codegpt.agent.koogJsonSerializer
import ee.carlrobert.codegpt.agent.strategy.CODE_AGENT_COMPRESSION
import ee.carlrobert.codegpt.agent.strategy.HistoryCompressionConfig
import ee.carlrobert.codegpt.agent.strategy.SingleRunStrategyProvider
import ee.carlrobert.codegpt.mcp.McpTool
import ee.carlrobert.codegpt.mcp.McpToolAliasResolver
import ee.carlrobert.codegpt.mcp.McpToolCallHandler
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.util.ReasoningFrameTextAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.*
import javax.swing.JPanel

internal object AgentCompletionRunner : CompletionRunner {

    private const val MAX_CHAT_AGENT_ITERATIONS = 32

    override fun run(request: CompletionRunnerRequest): CancellableRequest {
        val chatRequest = request as? CompletionRunnerRequest.Chat
            ?: throw IllegalArgumentException("AgentCompletionRunner can only run Chat requests")
        return executeChat(chatRequest)
    }

    private fun executeChat(request: CompletionRunnerRequest.Chat): CancellableRequest {
        val availableTools = request.callParameters.mcpTools
            ?.takeIf {
                it.isNotEmpty() && request.callParameters.toolApprovalMode != ToolApprovalMode.BLOCK_ALL
            }
            ?: emptyList()

        val messageBuilder = StringBuilder()
        val stream = shouldStreamAgentToolLoop(request)
        val toolCallHandler = request.project.let { McpToolCallHandler.getInstance(it) }
        val toolRegistry = createToolRegistry(
            callParameters = request.callParameters,
            mcpTools = availableTools,
            toolCallHandler = toolCallHandler,
            onToolCallUIUpdate = request.onToolCallUIUpdate
        )

        val events = object : AgentEvents {
            override fun onTextReceived(text: String) {
                if (text.isNotEmpty()) {
                    messageBuilder.append(text)
                    request.eventListener.onMessage(text)
                }
            }

            override fun onQueuedMessagesResolved(message: MessageWithContext?) = Unit
        }

        val asyncRequest = AsyncRequestContext {
            val conversationId = request.callParameters.conversation.id
            toolCallHandler.cancelPendingApprovals(conversationId)
            toolCallHandler.cancelExecutions(conversationId)
        }
        val scope = asyncRequest.scope
        request.eventListener.onOpen()

        val job = scope.launch {
            var service: GraphAIAgentService<MessageWithContext, String>?
            try {
                service = AIAgentService<MessageWithContext, String>(
                    promptExecutor = request.executor,
                    strategy = SingleRunStrategyProvider().build(
                        project = request.project,
                        executor = request.executor,
                        pendingMessageQueue = ArrayDeque<MessageWithContext>(),
                        historyCompressionConfig = HistoryCompressionConfig(
                            isLimitExceeded = { _, _ -> false },
                            compressionStrategy = CODE_AGENT_COMPRESSION
                        ),
                        events = events,
                        sessionId = request.callParameters.sessionId?.toString()
                            ?: request.callParameters.conversation.id.toString(),
                        provider = request.serviceType,
                        stream = stream
                    ),
                    agentConfig = AIAgentConfig(
                        prompt = prompt("chat-completion-agent") {
                            request.prompt.messages.forEach { message(it) }
                        },
                        model = request.model,
                        maxAgentIterations = MAX_CHAT_AGENT_ITERATIONS,
                        serializer = koogJsonSerializer
                    ),
                    toolRegistry = toolRegistry
                ) {
                    val frameAdapter = ReasoningFrameTextAdapter()
                    install(MessageTokenizer) {
                        tokenizer = object : Tokenizer {
                            override fun countTokens(text: String): Int {
                                return EncodingManager.getInstance().countTokens(text)
                            }
                        }
                        enableCaching = false
                    }
                    handleEvents {
                        onLLMStreamingFrameReceived { ctx ->
                            if (!stream) return@onLLMStreamingFrameReceived

                            frameAdapter.consume(ctx.streamFrame).forEach { chunk ->
                                if (chunk.isNotEmpty()) {
                                    events.onTextReceived(chunk)
                                }
                            }
                        }

                        onNodeExecutionCompleted { ctx ->
                            if (stream) return@onNodeExecutionCompleted

                            (ctx.output as? List<*>)?.forEach { msg ->
                                (msg as? Message.Assistant)?.let {
                                    events.onTextReceived(it.content)
                                }
                                (msg as? Message.Reasoning)?.let {
                                    if (it.content.isNotBlank()) {
                                        events.onThinkingReceived(it.content)
                                    }
                                }
                            }
                        }
                    }
                }

                val agent = service.createAgent()
                agent.run(MessageWithContext(""))

                if (asyncRequest.isCancelled()) {
                    request.eventListener.onCancelled(StringBuilder(messageBuilder))
                    return@launch
                }

                request.eventListener.onComplete(StringBuilder(messageBuilder))
            } catch (_: CancellationException) {
                request.eventListener.onCancelled(StringBuilder(messageBuilder))
            } catch (exception: Throwable) {
                request.eventListener.onError(
                    ChatError(exception.message ?: "Failed to complete request"),
                    exception
                )
            } finally {
                runCatching {
                    request.executor.close()
                    toolCallHandler.clearConversationState(request.callParameters.conversation.id)
                    scope.cancel()
                }
            }
        }
        asyncRequest.attach(job)

        return asyncRequest.cancellableRequest
    }

    internal fun shouldStreamAgentToolLoop(request: CompletionRunnerRequest.Chat): Boolean {
        val provider = request.serviceType
        return when (provider) {
            ServiceType.CUSTOM_OPENAI -> {
                val selectedServiceId = service<ModelSettings>()
                    .getModelSelectionForFeature(request.callParameters.featureType)
                    .serviceId
                service<CustomServicesSettings>()
                    .state.services
                    .firstOrNull { it.id == selectedServiceId }
                    ?.chatCompletionSettings
                    ?.shouldStream()
                    ?: false
            }

            ServiceType.GOOGLE -> false
            else -> true
        }
    }

    private fun createToolRegistry(
        callParameters: ChatCompletionParameters,
        mcpTools: List<McpTool>,
        toolCallHandler: McpToolCallHandler?,
        onToolCallUIUpdate: (JPanel) -> Unit
    ): ToolRegistry {
        if (mcpTools.isEmpty() || toolCallHandler == null) {
            return ToolRegistry {}
        }

        val aliases = McpToolAliasResolver.resolve(
            items = mcpTools,
            toolName = { it.name },
            scopeName = { it.serverId }
        )

        return ToolRegistry {
            aliases.forEach { (tool, alias) ->
                tool(
                    McpToolAdapter(
                        tool = tool,
                        exposedName = alias,
                        callParameters = callParameters,
                        toolCallHandler = toolCallHandler,
                        onToolCallUIUpdate = onToolCallUIUpdate
                    )
                )
            }
        }
    }
}
