package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.feature.handler.tool.ToolCallCompletedContext
import ai.koog.agents.core.feature.handler.tool.ToolCallStartingContext
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.agent.*
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.ProxyAISubagent
import ee.carlrobert.codegpt.settings.agents.SubagentDefaults
import ee.carlrobert.codegpt.settings.hooks.HookEventType
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.tokens.truncateToolResult
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * A tool for launching specialized agents to handle complex, multistep tasks autonomously.
 */
class TaskTool(
    private val project: Project,
    private val sessionId: String,
    private val provider: ServiceType,
    private val events: AgentEvents,
    private val hookManager: HookManager,
) : BaseTool<TaskTool.Args, TaskTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "Task",
    description = buildTaskDescription(project),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
) {

    @Serializable
    data class Args(
        @property:LLMDescription(
            "A short (3-5 word) description of the task. This serves as a label for the task."
        )
        val description: String,
        @property:LLMDescription(
            "The detailed task description for the agent to perform autonomously. This should contain " +
                    "clear instructions on what needs to be done, specific context and requirements, " +
                    "expected output or deliverables, and any constraints or preferences."
        )
        val prompt: String,
        @property:LLMDescription(
            "The type of specialized agent to use. Must match one of: " +
                    "\"general-purpose\" - For complex research, code search, and multi-step tasks, " +
                    "\"explore\" - Fast exploration of codebases, finding files and patterns, " +
                    "\"plan\" - Software architecture and implementation planning"
        )
        @SerialName("subagent_type")
        val subagentType: String,
        @property:LLMDescription(
            "Optional model override for this specific task"
        )
        val model: String? = null,
        @property:LLMDescription(
            "Optional project path for file operations and context"
        )
        @SerialName("project_path")
        val projectPath: String? = null
    )

    @Serializable
    data class Result(
        val agentType: String,
        val description: String,
        val prompt: String,
        val output: String,
        val executionTime: Long,
        val totalTokens: Long = 0
    )

    override suspend fun doExecute(args: Args): Result {
        val startTime = System.currentTimeMillis()
        val parentId = ToolRunContext.getToolId(sessionId)
            ?: throw IllegalStateException("No parent tool call found for session $sessionId")
        val totalTokenCounter = AtomicLong(0L)

        val startPayload = mapOf(
            "subagent_type" to args.subagentType,
            "description" to args.description,
            "prompt" to args.prompt
        )
        val toolId = ToolRunContext.getToolId(sessionId)
        val deniedReason = hookManager.checkHooksForDenial(
            HookEventType.SUBAGENT_START,
            startPayload,
            "Task",
            toolId,
            sessionId
        )
        if (deniedReason != null) {
            return Result(
                agentType = args.subagentType,
                description = args.description,
                prompt = args.prompt,
                output = deniedReason,
                executionTime = 0L
            )
        }

        val trackingEvents = object : AgentEvents by events {
            override fun onTokenUsageAvailable(tokenUsage: Long) {
                totalTokenCounter.addAndGet(tokenUsage)
                events.onTokenUsageAvailable(tokenUsage)
            }
        }

        try {
            val toolCallBridge = SubagentToolCallBridge(trackingEvents, parentId, sessionId)
            val approvalHandler = approvalHandler(trackingEvents)
            val configuredSubagent = if (isBuiltInAgentType(args.subagentType)) {
                null
            } else {
                findConfiguredSubagent(project, args.subagentType)
            }
            val agent = if (configuredSubagent != null) {
                AgentFactory.createManualAgent(
                    provider,
                    project,
                    sessionId,
                    configuredSubagent.title,
                    configuredSubagent.behavior,
                    configuredSubagent.tools,
                    approveToolCall = approvalHandler,
                    onAgentToolCallStarting = toolCallBridge::onToolCallStarting,
                    onAgentToolCallCompleted = toolCallBridge::onToolCallCompleted,
                    onCreditsAvailable = trackingEvents::onCreditsAvailable,
                    tokenCounter = totalTokenCounter,
                    hookManager = hookManager
                )
            } else {
                val agentType = AgentType.fromString(args.subagentType)
                val builtInConfig = lookupBuiltInConfig(project, agentType)
                val extraBehavior = builtInConfig?.objective?.takeIf { it.isNotBlank() }
                val toolOverrides = builtInConfig?.let { SubagentTool.parse(it.tools) }
                AgentFactory.createAgent(
                    agentType,
                    provider,
                    project,
                    sessionId,
                    approveToolCall = approvalHandler,
                    onAgentToolCallStarting = toolCallBridge::onToolCallStarting,
                    onAgentToolCallCompleted = toolCallBridge::onToolCallCompleted,
                    onCreditsAvailable = trackingEvents::onCreditsAvailable,
                    tokenCounter = totalTokenCounter,
                    extraBehavior = extraBehavior,
                    toolOverrides = toolOverrides,
                    hookManager = hookManager
                )
            }

            toolCallBridge.setToolRegistry((agent as? GraphAIAgent<*, *>)?.toolRegistry)

            val output = agent.run(args.prompt)
            val result = args.createResult(output, startTime, totalTokenCounter.get())

            val stopPayload = mapOf(
                "subagent_type" to args.subagentType,
                "status" to if (result.output.startsWith("Error:")) "error" else "completed",
                "result" to result.output,
                "duration" to result.executionTime
            )
            val toolId = ToolRunContext.getToolId(sessionId)
            hookManager.executeHooksForEvent(
                HookEventType.SUBAGENT_STOP,
                stopPayload,
                "Task",
                toolId,
                sessionId
            )

            return result
        } catch (e: Exception) {
            val stopPayload = mapOf(
                "subagent_type" to args.subagentType,
                "status" to "error",
                "result" to "Error: ${e.message}",
                "duration" to System.currentTimeMillis() - startTime
            )
            val toolId = ToolRunContext.getToolId(sessionId)
            hookManager.executeHooksForEvent(
                HookEventType.SUBAGENT_STOP,
                stopPayload,
                "Task",
                toolId,
                sessionId
            )
            return Result(
                agentType = args.subagentType,
                description = args.description,
                prompt = args.prompt,
                output = "Error: ${e.message}",
                executionTime = System.currentTimeMillis() - startTime,
            )
        }
    }

    override fun createDeniedResult(
        originalArgs: Args,
        deniedReason: String
    ): Result {
        return Result(
            agentType = originalArgs.subagentType,
            description = originalArgs.description,
            prompt = originalArgs.prompt,
            output = deniedReason,
            executionTime = 0L
        )
    }

    private fun Args.createResult(output: String, startTime: Long, totalTokens: Long): Result {
        val resolvedTotalTokens =
            if (totalTokens > 0) totalTokens else EncodingManager.getInstance()
                .countTokens(output)
                .toLong()
        return Result(
            agentType = this.subagentType,
            description = this.description,
            prompt = this.prompt,
            output = output,
            executionTime = System.currentTimeMillis() - startTime,
            totalTokens = resolvedTotalTokens
        )
    }

    override fun encodeResultToString(result: Result): String {
        val summary = buildString {
            appendLine("Agent: ${result.agentType}")
            appendLine("Description: ${result.description}")
            appendLine("DurationMs: ${result.executionTime}")
            if (result.totalTokens > 0) {
                appendLine("TotalTokens: ${formatTokens(result.totalTokens)}")
            }
            appendLine()
            appendLine("Output:")
            appendLine(result.output)
        }.trimEnd()
        return summary.truncateToolResult()
    }

    private fun formatTokens(tokens: Long): String {
        return if (tokens >= 1000) {
            "${tokens / 1000}K"
        } else {
            tokens.toString()
        }
    }
}

private fun buildTaskDescription(project: Project): String {
    return buildString {
        appendLine(
            """
                Launch a new agent to handle complex, multi-step tasks autonomously.

                Built-in agent types:
                - general-purpose: General-purpose agent for researching complex questions, searching for code, and executing multi-step tasks.
                - explore: Fast agent specialized for exploring codebases.

                Custom subagents:
                - You may also pass the exact name of a configured subagent (as shown in ProxyAI Settings > Subagents) in the subagent_type field.

                Usage notes:
                - Use a single message with multiple tool calls to launch agents in parallel when helpful.
                - Provide a detailed prompt and expected output.
                """.trimIndent()
        )

        val subagents = runCatching {
            project.service<ProxyAISettingsService>().getSubagents()
                .filterNot { SubagentDefaults.isBuiltInId(it.id) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (subagents != null) {
            appendLine()
            appendLine("Configured subagents available:")
            subagents
                .filter { it.title.trim().isNotBlank() }
                .forEach { sa ->
                    append("- ")
                    append(sa.title)
                    val desc = sa.objective.trim()
                    if (desc.isNotBlank()) {
                        append(": ")
                        append(desc.take(140))
                    }
                    appendLine()
                }
        }
    }.trimEnd()
}

private data class ConfiguredSubagent(
    val title: String,
    val behavior: String,
    val tools: Set<String>
)

private fun isBuiltInAgentType(value: String): Boolean {
    return when (value.lowercase().trim()) {
        "general-purpose", "explore" -> true
        else -> false
    }
}

private fun findConfiguredSubagent(project: Project, name: String): ConfiguredSubagent? {
    val subagent = runCatching {
        project.service<ProxyAISettingsService>().getSubagents()
            .firstOrNull {
                !SubagentDefaults.isBuiltInId(it.id) && it.title.equals(
                    name,
                    ignoreCase = true
                )
            }
    }.getOrNull() ?: return null
    val title = subagent.title.takeIf { it.isNotBlank() } ?: name
    return ConfiguredSubagent(title, subagent.objective, subagent.tools.toSet())
}

private fun lookupBuiltInConfig(project: Project, agentType: AgentType): ProxyAISubagent? {
    val id = SubagentDefaults.builtInIdFor(agentType) ?: return null
    return project.service<ProxyAISettingsService>().getSubagents().firstOrNull { it.id == id }
}

private fun approvalHandler(events: AgentEvents): suspend (String, String) -> Boolean =
    { name, details ->
        val approvalType = ToolSpecs.approvalTypeFor(name)
        events.approveToolCall(ToolApprovalRequest(approvalType, "Allow $name?", details))
    }

private class SubagentToolCallBridge(
    private val events: AgentEvents,
    private val parentId: String,
    private val sessionId: String
) {
    private val pendingChildIds = ArrayDeque<String>()
    private var toolRegistry: ToolRegistry? = null

    fun setToolRegistry(registry: ToolRegistry?) {
        toolRegistry = registry
    }

    fun onToolCallStarting(ctx: ToolCallStartingContext) {
        val tool = toolRegistry?.getToolOrNull(ctx.toolName) ?: return
        val decodedArgs = runCatching { tool.decodeArgs(ctx.toolArgs) }.getOrElse { ctx.toolArgs }
        val childId = events.onSubAgentToolStarting(parentId, ctx.toolName, decodedArgs)
        if (childId != null) {
            pendingChildIds.addLast(childId)
            ToolRunContext.set(sessionId, childId)
        }
    }

    fun onToolCallCompleted(ctx: ToolCallCompletedContext) {
        val tool = toolRegistry?.getToolOrNull(ctx.toolName) ?: return
        val toolResult = ctx.toolResult ?: return
        val childId = if (pendingChildIds.isEmpty()) null else pendingChildIds.removeFirst()
        val decodedResult = runCatching { tool.decodeResult(toolResult) }.getOrElse { toolResult }
        events.onSubAgentToolCompleted(
            parentId,
            childId,
            ctx.toolName,
            decodedResult
        )
    }
}
