package ee.carlrobert.codegpt.agent

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.RollbackStrategy
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.agents.ext.tool.shell.ShellCommandConfirmation
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.tokenizer.feature.MessageTokenizer
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageLogWriter
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.tokenizer.Tokenizer
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.agent.clients.shouldStream
import ee.carlrobert.codegpt.agent.strategy.CODE_AGENT_COMPRESSION
import ee.carlrobert.codegpt.agent.strategy.HistoryCompressionConfig
import ee.carlrobert.codegpt.agent.strategy.SingleRunStrategyProvider
import ee.carlrobert.codegpt.agent.strategy.buildHistoryTooBigPredicate
import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.hooks.HookEventType
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.skills.SkillDiscoveryService
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.BashPayload
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalRequest
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalType
import ee.carlrobert.codegpt.util.ReasoningFrameTextAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

data class ToolError(val message: String)

object ProxyAIAgent {

    private const val INSTRUCTION_FILE_NAME = "PROXYAI.md"
    private const val MAX_AGENT_ITERATIONS = 250

    private val logger = KotlinLogging.logger { }

    internal fun loadProjectInstructions(projectPath: String?): String? {
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

    fun createService(
        project: Project,
        checkpointStorage: JVMFilePersistenceStorageProvider,
        provider: ServiceType,
        events: AgentEvents,
        sessionId: String,
        pendingMessages: ConcurrentHashMap<String, ArrayDeque<MessageWithContext>>,
    ): GraphAIAgentService<MessageWithContext, String> {
        val modelSelection =
            service<ModelSettings>().getModelSelectionForFeature(FeatureType.AGENT)
        val skills = project.service<SkillDiscoveryService>().listSkills()
        val stream = shouldStreamAgentToolLoop(project, provider)
        val projectInstructions = loadProjectInstructions(project.basePath)
        val executor = AgentFactory.createExecutor(provider, events)
        val pendingMessageQueue = pendingMessages.getOrPut(sessionId) { ArrayDeque() }
        val hookManager = HookManager(project)
        val toolRegistry = createToolRegistry(
            project = project,
            events = events,
            sessionId = sessionId,
            provider = provider,
            parentModelSelection = modelSelection,
            hookManager = hookManager
        )
        val agentModel = service<ModelSettings>().getAgentModel()
        return AIAgentService<MessageWithContext, String>(
            promptExecutor = executor,
            strategy = SingleRunStrategyProvider().build(
                project,
                executor,
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
                            project.basePath,
                            skills
                        )
                    )

                    projectInstructions?.let {
                        message(
                            Message.User(
                                content = it,
                                metaInfo = RequestMetaInfo(
                                    Clock.System.now(),
                                    JsonObject(buildMap<String, JsonPrimitive> {
                                        put("cacheable", JsonPrimitive(true))
                                    })
                                )
                            )
                        )
                    }
                },
                model = agentModel,
                maxAgentIterations = MAX_AGENT_ITERATIONS
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
                enableAutomaticPersistence = true
                rollbackStrategy = RollbackStrategy.MessageHistoryOnly
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
                val frameAdapter = ReasoningFrameTextAdapter()
                var streamedReasoningForCurrentNode = false

                onLLMStreamingFrameReceived { ctx ->
                    if (!stream) return@onLLMStreamingFrameReceived

                    val frameType = ctx.streamFrame::class.simpleName
                        ?: ctx.streamFrame::class.qualifiedName
                        ?: "unknown"
                    val chunks = frameAdapter.consume(ctx.streamFrame)
                    if (frameType.contains("Reasoning") && chunks.isNotEmpty()) {
                        streamedReasoningForCurrentNode = true
                    }

                    chunks.forEach { chunk ->
                        if (chunk.isNotEmpty()) {
                            events.onTextReceived(chunk)
                        }
                    }
                }

                onNodeExecutionCompleted { ctx ->
                    val output = (ctx.output as? List<*>) ?: emptyList<Any?>()
                    if (stream) {
                        if (!streamedReasoningForCurrentNode) {
                            output.forEach { msg ->
                                (msg as? Message.Reasoning)?.let {
                                    if (it.content.isNotBlank()) {
                                        events.onTextReceived("<think>${it.content}</think>")
                                    }
                                }
                            }
                        }
                        streamedReasoningForCurrentNode = false
                        return@onNodeExecutionCompleted
                    }

                    output.forEach { msg ->
                        (msg as? Message.Assistant)?.let {
                            events.onTextReceived(it.content)
                        }
                        (msg as? Message.Reasoning)?.let {
                            if (it.content.isNotBlank()) {
                                events.onTextReceived("<think>${it.content}</think>")
                            }
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
                    val uiArgs = if (tool is McpAgentToolMarker && decodedArgs is JsonObject) {
                        tool.toDisplayArgs(decodedArgs)
                    } else {
                        decodedArgs
                    }
                    events.onToolStarting(id, ctx.toolName, uiArgs)
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
                    events.onAgentException(provider, it.throwable)
                }
            }
        }
    }

    private fun shouldStreamAgentToolLoop(
        project: Project,
        provider: ServiceType,
    ): Boolean {
        return when (provider) {
            ServiceType.CUSTOM_OPENAI -> {
                val selectedModel =
                    service<ModelSettings>().getModelSelectionForFeature(FeatureType.AGENT)
                val selectedServiceId = selectedModel.serviceId
                val selectedService = service<CustomServicesSettings>().state.services
                    .firstOrNull { it.id == selectedServiceId }
                selectedService?.chatCompletionSettings?.shouldStream() == true
            }

            ServiceType.GOOGLE -> false
            else -> true
        }
    }

    private fun createToolRegistry(
        project: Project,
        events: AgentEvents,
        sessionId: String,
        provider: ServiceType,
        parentModelSelection: ModelSelection,
        hookManager: HookManager
    ): ToolRegistry {
        val workingDirectory = project.basePath ?: System.getProperty("user.dir")
        val contextService = project.service<AgentMcpContextService>()
        val standardApproval = approvalHandler(events)
        val writeApproval = writeApprovalHandler(events)
        val genericApproval = genericApprovalHandler(events)
        return ToolRegistry {
            tool(ReadTool(project, sessionId, hookManager))
            tool(ConfirmingEditTool(EditTool(project, sessionId, hookManager), standardApproval))
            tool(ConfirmingWriteTool(WriteTool(project, sessionId, hookManager), writeApproval))
            tool(TodoWriteTool(project, sessionId, hookManager))
            tool(
                AskUserQuestionTool(
                    workingDirectory = workingDirectory,
                    sessionId = sessionId,
                    hookManager = hookManager,
                    events = events
                )
            )
            createMcpTools(
                sessionId = sessionId,
                contextService = contextService,
                approve = genericApproval
            ).forEach { mcpTool -> tool(mcpTool) }
            tool(ExitTool)
            tool(
                IntelliJSearchTool(
                    project = project,
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            tool(
                DiagnosticsTool(
                    project = project,
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            tool(
                WebSearchTool(
                    workingDirectory = workingDirectory,
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            tool(
                WebFetchTool(
                    workingDirectory = workingDirectory,
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            tool(
                BashOutputTool(
                    workingDirectory = workingDirectory,
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            tool(
                KillShellTool(
                    workingDirectory = workingDirectory,
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            tool(
                ResolveLibraryIdTool(
                    workingDirectory = workingDirectory,
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            tool(
                GetLibraryDocsTool(
                    workingDirectory = workingDirectory,
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            tool(
                ConfirmingLoadSkillTool(
                    LoadSkillTool(
                        project = project,
                        sessionId = sessionId,
                        hookManager = hookManager
                    ),
                    project = project
                ) { name, details -> genericApproval(name, details) }
            )
            tool(
                BashTool(
                    project = project,
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
                    hookManager = hookManager
                )
            )
            tool(
                TaskTool(
                    project,
                    sessionId,
                    parentModelSelection,
                    events,
                    hookManager
                )
            )
        }
    }

    private fun approvalHandler(events: AgentEvents): suspend (String, String) -> Boolean =
        approvalHandler(events, ToolSpecs::approvalTypeFor)

    private fun writeApprovalHandler(events: AgentEvents): suspend (String, String) -> Boolean =
        approvalHandler(events) { name ->
            if (name.equals("Write", ignoreCase = true)) {
                ToolApprovalType.WRITE
            } else {
                ToolApprovalType.GENERIC
            }
        }

    private fun genericApprovalHandler(events: AgentEvents): suspend (String, String) -> Boolean =
        approvalHandler(events) { ToolApprovalType.GENERIC }

    private fun approvalHandler(
        events: AgentEvents,
        approvalType: (String) -> ToolApprovalType
    ): suspend (String, String) -> Boolean {
        return { name, details ->
            safeApprove(
                events = events,
                request = ToolApprovalRequest(
                    approvalType(name),
                    "Allow $name?",
                    details
                ),
                fallback = false
            )
        }
    }

    private fun createMcpTools(
        sessionId: String,
        contextService: AgentMcpContextService,
        approve: suspend (String, String) -> Boolean
    ): List<Tool<*, *>> {
        val context = contextService.get(sessionId) ?: return emptyList()
        if (!context.hasSelection() || context.conversationId == null) {
            return emptyList()
        }
        return McpDynamicToolRegistry.createTools(context, approve)
    }

    private suspend fun safeApprove(
        events: AgentEvents,
        request: ToolApprovalRequest,
        fallback: Boolean
    ): Boolean {
        return runCatching { events.approveToolCall(request) }.getOrDefault(fallback)
    }

    private fun computeAvailableInput(model: LLModel): Long {
        val contextLength = model.contextLength ?: 128_000L
        val outputLength = model.maxOutputTokens ?: 0L
        return (contextLength - outputLength).coerceAtLeast(1L)
    }
}
