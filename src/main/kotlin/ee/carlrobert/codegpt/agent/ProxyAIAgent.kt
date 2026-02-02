package ee.carlrobert.codegpt.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.RollbackStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.agents.ext.tool.shell.ShellCommandConfirmation
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.tokenizer.feature.MessageTokenizer
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageLogWriter
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.snapshot.feature.persistence
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.tokenizer.Tokenizer
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.agent.strategy.CODE_AGENT_COMPRESSION
import ee.carlrobert.codegpt.agent.strategy.HistoryCompressionConfig
import ee.carlrobert.codegpt.agent.strategy.SingleRunStrategyProvider
import ee.carlrobert.codegpt.agent.strategy.buildHistoryTooBigPredicate
import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.hooks.HookEventType
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.BashPayload
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalRequest
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalType
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.typeOf

data class ToolError(val message: String)

object ProxyAIAgent {

    private const val INSTRUCTION_FILE_NAME = "PROXYAI.md"

    private val logger = KotlinLogging.logger { }
    private val messageWithContextSnapshotType = typeOf<MessageWithContextSnapshot>()

    private fun searchForInstructions(projectPath: String?): String? {
        if (projectPath == null) return null
        val projectRoot = Paths.get(projectPath)
        if (!Files.exists(projectRoot)) return null

        val proxyAiFile = projectRoot.resolve(INSTRUCTION_FILE_NAME)
        if (Files.exists(proxyAiFile) && Files.isRegularFile(proxyAiFile)) {
            try {
                val content = Files.readString(proxyAiFile).trim()
                if (content.isNotEmpty()) {
                    return content
                }
            } catch (_: Exception) {
                logger.warn { "Couldn't read $INSTRUCTION_FILE_NAME" }
            }
        }

        return null
    }

    fun create(
        project: Project,
        checkpointStorage: JVMFilePersistenceStorageProvider,
        previousCheckpoint: AgentCheckpointData?,
        provider: ServiceType,
        events: AgentEvents,
        sessionId: String,
        pendingMessages: ConcurrentHashMap<String, ArrayDeque<MessageWithContext>>,
    ): AIAgent<MessageWithContext, String> {
        val modelSelection =
            service<ModelSelectionService>().getModelSelectionForFeature(FeatureType.AGENT)
        val stream = provider != ServiceType.CUSTOM_OPENAI
        val projectInstructions = searchForInstructions(project.basePath)
        val executor = AgentFactory.createExecutor(provider, events)
        val pendingMessageQueue = pendingMessages.getOrPut(sessionId) { ArrayDeque() }
        val hookManager = HookManager(project)
        val toolRegistry = createToolRegistry(project, events, sessionId, provider, hookManager)
        val agentModel = service<ModelSelectionService>().getAgentModel()
        val agent = AIAgent(
            promptExecutor = executor,
            strategy = SingleRunStrategyProvider().build(
                project,
                executor,
                projectInstructions,
                previousCheckpoint,
                pendingMessageQueue,
                HistoryCompressionConfig(
                    isLimitExceeded = buildHistoryTooBigPredicate(computeAvailableInput(agentModel)),
                    compressionStrategy = CODE_AGENT_COMPRESSION
                ),
                events,
                sessionId,
                provider,
                stream
            ),
            agentConfig = AIAgentConfig(
                prompt = prompt("proxyai-agent") {
                    system(
                        AgentSystemPrompts.createSystemPrompt(
                            provider,
                            modelSelection,
                            project.basePath
                        )
                    )
                },
                model = agentModel,
                maxAgentIterations = 100
            ),
            toolRegistry = toolRegistry,
        ) {
            if (ConfigurationSettings.getState().debugModeEnabled) {
                install(Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            }
            install(Persistence) {
                storage = checkpointStorage
                rollbackStrategy = RollbackStrategy.Default
            }
            install(MessageTokenizer) {
                tokenizer = object : Tokenizer {
                    override fun countTokens(text: String): Int =
                        EncodingManager.getInstance().countTokens(text)
                }
                enableCaching = false
            }

            handleEvents {
                val toolCallToUiId: MutableMap<String, String> = HashMap()
                val anonymousToolIds: ArrayDeque<String> = ArrayDeque()

                onLLMStreamingFrameReceived { ctx ->
                    if (!stream) return@onLLMStreamingFrameReceived

                    val frame = ctx.streamFrame
                    if (frame is StreamFrame.Append) {
                        events.onTextReceived(frame.text)
                    }
                }

                onNodeExecutionCompleted { ctx ->
                    val input = ctx.input
                    val (lastInput, lastInputType) = if (input is MessageWithContext) {
                        val snapshot = input.toSnapshot()
                        snapshot to messageWithContextSnapshotType
                    } else {
                        input to ctx.inputType
                    }
                    val checkpoint = ctx.context.persistence().createCheckpoint(
                        agentContext = ctx.context,
                        nodePath = ctx.context.executionInfo.path(),
                        lastInput = lastInput,
                        lastInputType = lastInputType,
                        checkpointId = ctx.context.runId,
                        version = 0L
                    )
                    checkpoint?.checkpointId ?: return@onNodeExecutionCompleted

                    if (stream) return@onNodeExecutionCompleted

                    (ctx.output as? List<*>)?.forEach { msg ->
                        (msg as? Message.Assistant)?.let {
                            events.onTextReceived(it.content)
                        }
                        (msg as? Message.Reasoning)?.let {
                            events.onTextReceived(it.content)
                        }
                    }
                }

                onNodeExecutionFailed { ctx ->
                    logger.error(ctx.throwable) { "Node execution failed: $ctx" }
                }

                onToolCallStarting { ctx ->
                    val tool = toolRegistry.getToolOrNull(ctx.toolName)
                    if (tool == null) {
                        logger.warn { "Ignoring undefined tool call: ${ctx.toolName}" }
                        return@onToolCallStarting
                    }

                    val id = ctx.toolCallId ?: UUID.randomUUID().toString()
                    if (ctx.toolCallId == null) {
                        anonymousToolIds.addLast(id)
                    }
                    val decodedArgs =
                        runCatching { tool.decodeArgs(ctx.toolArgs) }.getOrElse { ctx.toolArgs }
                    events.onToolStarting(id, ctx.toolName, decodedArgs)
                    ToolRunContext.set(sessionId, id)
                }

                onToolCallCompleted { ctx ->
                    val tool = toolRegistry.getToolOrNull(ctx.toolName)
                    if (tool == null) {
                        logger.warn { "Ignoring undefined tool completion: ${ctx.toolName}" }
                        return@onToolCallCompleted
                    }

                    val toolResult = ctx.toolResult
                    if (toolResult == null) {
                        logger.warn { "Ignoring undefined tool result: $toolResult" }
                        return@onToolCallCompleted
                    }

                    val uiId = when {
                        ctx.toolCallId != null -> toolCallToUiId[ctx.toolCallId] ?: ctx.toolCallId
                        anonymousToolIds.isNotEmpty() -> anonymousToolIds.removeFirst()
                        else -> null
                    }
                    val decodedResult =
                        runCatching { tool.decodeResult(toolResult) }.getOrElse { toolResult }
                    events.onToolCompleted(uiId, ctx.toolName, decodedResult)
                }

                onAgentCompleted { context ->
                    hookManager.executeHooksForEvent(
                        HookEventType.STOP,
                        mapOf(
                            "status" to "completed",
                            "agent_id" to context.agentId
                        ),
                        sessionId = sessionId
                    )
                    events.onAgentCompleted(context.agentId)
                }

                onAgentExecutionFailed {
                    logger.error(it.throwable) { "Agent execution failed: $it" }
                    hookManager.executeHooksForEvent(
                        HookEventType.STOP,
                        mapOf(
                            "status" to "error",
                            "agent_id" to it.agentId,
                            "error" to (it.throwable.message ?: "Unknown error")
                        ),
                        sessionId = sessionId
                    )
                    events.onAgentCompleted(it.agentId)
                }
            }
        }
        return agent
    }

    private fun createToolRegistry(
        project: Project,
        events: AgentEvents,
        sessionId: String,
        provider: ServiceType,
        hookManager: HookManager
    ): ToolRegistry {
        val workingDirectory = project.basePath ?: System.getProperty("user.dir")
        return ToolRegistry {
            tool(ReadTool(project, hookManager, sessionId))
            val approveHandler: suspend (String, String) -> Boolean = { name, details ->
                try {
                    events.approveToolCall(
                        ToolApprovalRequest(
                            ToolSpecs.approvalTypeFor(name),
                            "Allow $name?",
                            details
                        )
                    )
                } catch (_: Exception) {
                    false
                }
            }
            tool(ConfirmingEditTool(EditTool(project, hookManager, sessionId), approveHandler))
            tool(
                ConfirmingWriteTool(WriteTool(project, hookManager)) { name, details ->
                    try {
                        val type = if (name.equals("Write", true))
                            ToolApprovalType.WRITE
                        else ToolApprovalType.GENERIC

                        events.approveToolCall(
                            ToolApprovalRequest(
                                type,
                                "Allow $name?",
                                details
                            )
                        )
                    } catch (_: Exception) {
                        false
                    }
                }
            )
            tool(TodoWriteTool(project, sessionId, hookManager))
            tool(
                AskUserQuestionTool(
                    workingDirectory = workingDirectory,
                    hookManager = hookManager,
                    events = events
                )
            )
            tool(ExitTool)
            tool(IntelliJSearchTool(project = project, hookManager = hookManager))
            tool(
                WebSearchTool(
                    workingDirectory = workingDirectory,
                    hookManager = hookManager,
                )
            )
            tool(
                BashOutputTool(
                    workingDirectory = workingDirectory,
                    hookManager = hookManager,
                    sessionId = sessionId
                )
            )
            tool(
                KillShellTool(
                    workingDirectory = workingDirectory,
                    hookManager = hookManager,
                )
            )
            tool(
                ResolveLibraryIdTool(
                    workingDirectory = workingDirectory,
                    hookManager = hookManager,
                )
            )
            tool(
                GetLibraryDocsTool(
                    workingDirectory = workingDirectory,
                    hookManager = hookManager
                )
            )
            tool(
                BashTool(
                    workingDirectory = workingDirectory,
                    confirmationHandler = { args ->
                        try {
                            val approved = events.approveToolCall(
                                ToolApprovalRequest(
                                    ToolApprovalType.BASH,
                                    "Run shell command?",
                                    args.command,
                                    BashPayload(args.command, args.description)
                                )
                            )
                            if (approved) ShellCommandConfirmation.Approved else ShellCommandConfirmation.Denied(
                                "User rejected the command"
                            )
                        } catch (_: Exception) {
                            ShellCommandConfirmation.Approved
                        }
                    },
                    sessionId = sessionId,
                    settingsService = project.service<ProxyAISettingsService>(),
                    hookManager = hookManager
                )
            )
            tool(
                TaskTool(
                    project,
                    sessionId,
                    service<ModelSelectionService>().getServiceForFeature(FeatureType.AGENT),
                    events,
                    hookManager
                )
            )
        }
    }

    private fun computeAvailableInput(model: LLModel): Long {
        val contextLength = model.contextLength
        val outputLength = model.maxOutputTokens ?: 0
        return (contextLength - outputLength).coerceAtLeast(1L)
    }
}
