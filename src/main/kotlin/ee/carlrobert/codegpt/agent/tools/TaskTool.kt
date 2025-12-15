package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.feature.handler.tool.ToolCallCompletedContext
import ai.koog.agents.core.feature.handler.tool.ToolCallStartingContext
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.agent.*
import ee.carlrobert.codegpt.conversations.message.TokenUsage
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.ProxyAISubagent
import ee.carlrobert.codegpt.settings.agents.SubagentDefaults
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.tokens.truncateToolResult
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalRequest
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A tool for launching specialized agents to handle complex, multistep tasks autonomously.
 */
class TaskTool(
    private val project: Project,
    private val sessionId: String,
    private val provider: ServiceType,
    private val events: AgentEvents,
) : Tool<TaskTool.Args, TaskTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "Task",
    description = buildTaskDescription(project)
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

    internal data class InternalResult(
        val agentType: String,
        val description: String,
        val prompt: String,
        val output: String,
        val executionTime: Long,
        val totalTokens: Long = 0,
        val tokenUsage: TokenUsage? = null
    )

    override suspend fun execute(args: Args): Result {
        val startTime = System.currentTimeMillis()
        val parentId = ToolRunContext.getToolId(sessionId)
            ?: throw IllegalStateException("No parent tool call found for session $sessionId")

        try {
            val toolCallBridge = SubagentToolCallBridge(events, parentId, sessionId)
            val approvalHandler = approvalHandler(events)
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
                    onCreditsAvailable = events::onCreditsAvailable
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
                    extraBehavior = extraBehavior,
                    toolOverrides = toolOverrides,
                    onCreditsAvailable = events::onCreditsAvailable
                )
            }

            toolCallBridge.setToolRegistry((agent as? GraphAIAgent<*, *>)?.toolRegistry)

            val output = agent.run(args.prompt)
            return args.createResult(output, startTime)
        } catch (e: Exception) {
            return Result(
                agentType = args.subagentType,
                description = args.description,
                prompt = args.prompt,
                output = "Error: ${e.message}",
                executionTime = System.currentTimeMillis() - startTime,
            )
        }
    }

    private fun Args.createResult(output: String, startTime: Long): Result {
        return Result(
            agentType = this.subagentType,
            description = this.description,
            prompt = this.prompt,
            output = output,
            executionTime = System.currentTimeMillis() - startTime,
            totalTokens = EncodingManager.getInstance().countTokens(output).toLong()
        )
    }

    override fun encodeResultToString(result: Result): String {
        val summary = buildString {
            appendLine("Agent: ${result.agentType}")
            appendLine("Description: ${result.description}")
            appendLine("DurationMs: ${result.executionTime}")
            if (result.totalTokens > 0) {
                appendLine("TotalTokens: ${result.totalTokens}")
            }
            appendLine()
            appendLine("Output:")
            appendLine(result.output)
        }.trimEnd()
        return summary.truncateToolResult()
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
                - plan: Software architect agent for designing implementation plans.

                Custom subagents:
                - You may also pass the exact name of a configured subagent (as shown in ProxyAI Settings > Subagents) in the subagent_type field.

                Usage notes:
                - Use a single message with multiple tool calls to launch agents in parallel when helpful.
                - Provide a detailed prompt and expected output.
                """.trimIndent()
        )

        val customs = runCatching {
            project.service<ProxyAISettingsService>().getSubagents()
                .filterNot { SubagentDefaults.isBuiltInId(it.id) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (customs != null) {
            appendLine()
            appendLine("Configured subagents available:")
            customs.forEach { sa ->
                val title = sa.title.trim()
                if (title.isEmpty()) return@forEach
                val desc = sa.objective.trim()
                append("- ")
                append(title)
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
        "general-purpose", "explore", "plan" -> true
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
        events.approveToolCall(ToolApprovalRequest(approvalTypeFor(name), "Allow $name?", details))
    }

private fun approvalTypeFor(name: String): ToolApprovalType {
    return when {
        name.equals("Write", true) -> ToolApprovalType.WRITE
        name.equals("Edit", true) -> ToolApprovalType.EDIT
        name.equals("Bash", true) -> ToolApprovalType.BASH
        else -> ToolApprovalType.GENERIC
    }
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

    fun onToolCallStarting(eventContext: ToolCallStartingContext) {
        val decodedArgs = ToolCallPayloadDecoder.decodeArgs(
            toolRegistry,
            eventContext.toolName,
            eventContext.toolArgs
        )
        val childId = events.onSubAgentToolStarting(parentId, eventContext.toolName, decodedArgs)
        if (childId != null) {
            pendingChildIds.addLast(childId)
            ToolRunContext.set(sessionId, childId)
        }
    }

    fun onToolCallCompleted(eventContext: ToolCallCompletedContext) {
        val childId = if (pendingChildIds.isEmpty()) null else pendingChildIds.removeFirst()
        val decodedResult = ToolCallPayloadDecoder.decodeResult(
            toolRegistry,
            eventContext.toolName,
            eventContext.toolResult
        )
        events.onSubAgentToolCompleted(
            parentId,
            childId,
            eventContext.toolName,
            decodedResult
        )
    }
}
