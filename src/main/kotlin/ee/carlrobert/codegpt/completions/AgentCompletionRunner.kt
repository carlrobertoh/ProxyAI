package ee.carlrobert.codegpt.completions

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.tokenizer.feature.MessageTokenizer
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.tokenizer.Tokenizer
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.agent.AgentEvents
import ee.carlrobert.codegpt.agent.MessageWithContext
import ee.carlrobert.codegpt.agent.clients.shouldStream
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
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cancelled = AtomicBoolean(false)
        val jobRef = AtomicReference<Job?>()
        val messageBuilder = StringBuilder()
        val project = request.callParameters.project!!
        val stream = shouldStreamAgentToolLoop(project, request)
        val toolCallHandler = project.let { McpToolCallHandler.getInstance(it) }
        val toolRegistry = createChatToolRegistry(
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

            override fun onQueuedMessagesResolved() = Unit
        }

        val cancellableRequest = CancellableRequest {
            cancelled.set(true)
            val conversationId = request.callParameters.conversation.id
            toolCallHandler.cancelPendingApprovals(conversationId)
            toolCallHandler.cancelExecutions(conversationId)
            jobRef.get()?.cancel(CancellationException("Cancelled by user"))
        }
        request.eventListener.onOpen()

        val job = scope.launch {
            var service: GraphAIAgentService<MessageWithContext, String>? = null
            try {
                service = AIAgentService<MessageWithContext, String>(
                    promptExecutor = request.executor,
                    strategy = SingleRunStrategyProvider().build(
                        project = project,
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
                        maxAgentIterations = MAX_CHAT_AGENT_ITERATIONS
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
                                (msg as? ai.koog.prompt.message.Message.Assistant)?.let {
                                    events.onTextReceived(it.content)
                                }
                                (msg as? ai.koog.prompt.message.Message.Reasoning)?.let {
                                    if (it.content.isNotBlank()) {
                                        events.onTextReceived("<think>${it.content}</think>")
                                    }
                                }
                            }
                        }
                    }
                }

                val agent = service.createAgent()
                agent.run(MessageWithContext(""))

                if (cancelled.get()) {
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
                    service?.closeAll()
                    request.executor.close()
                    toolCallHandler.clearConversationState(request.callParameters.conversation.id)
                    scope.cancel()
                }
            }
        }
        jobRef.set(job)

        return cancellableRequest
    }

    internal fun shouldStreamAgentToolLoop(
        project: Project,
        request: CompletionRunnerRequest.Chat,
    ): Boolean {
        val provider = request.serviceType
        return when (provider) {
            ServiceType.CUSTOM_OPENAI -> {
                val selectedServiceId = project.service<ModelSettings>()
                    .getStoredModelForFeature(request.callParameters.featureType)
                project.service<CustomServicesSettings>().state.services
                    .firstOrNull { it.id == selectedServiceId }
                    ?.chatCompletionSettings
                    ?.shouldStream()
                    ?: false
            }

            ServiceType.GOOGLE -> false
            else -> true
        }
    }

    private fun createChatToolRegistry(
        callParameters: ChatCompletionParameters,
        mcpTools: List<McpTool>,
        toolCallHandler: McpToolCallHandler?,
        onToolCallUIUpdate: (javax.swing.JPanel) -> Unit
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
