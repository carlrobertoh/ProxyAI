package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.AgentMcpContext
import ee.carlrobert.codegpt.agent.AgentMcpContextService
import ee.carlrobert.codegpt.mcp.ConnectionStatus
import ee.carlrobert.codegpt.mcp.McpSessionAttachment
import ee.carlrobert.codegpt.mcp.McpSessionManager
import ee.carlrobert.codegpt.settings.hooks.HookManager
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.*

class McpTool(
    private val project: Project,
    private val sessionId: String,
    context: AgentMcpContext,
    private val sessionManager: McpSessionManager,
    hookManager: HookManager
) : BaseTool<McpTool.Args, McpTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "MCP",
    description = buildToolDescription(context, sessionManager),
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    hookManager = hookManager,
    sessionId = sessionId,
    argsClass = Args::class,
    resultClass = Result::class
) {

    @Serializable
    data class Args(
        @property:LLMDescription(
            "Tool name to execute on the MCP server"
        )
        @SerialName("tool_name")
        val toolName: String,
        @property:LLMDescription(
            "Optional MCP server id when multiple servers expose the same tool"
        )
        @SerialName("server_id")
        val serverId: String? = null,
        @property:LLMDescription(
            "Optional MCP server name alternative to server_id"
        )
        @SerialName("server_name")
        val serverName: String? = null,
        @property:LLMDescription(
            "JSON object with tool arguments"
        )
        val arguments: Map<String, JsonElement> = emptyMap()
    )

    @Serializable
    data class Result(
        @SerialName("server_id")
        val serverId: String?,
        @SerialName("server_name")
        val serverName: String?,
        @SerialName("tool_name")
        val toolName: String,
        val success: Boolean,
        val output: String
    ) {
        companion object {
            fun error(
                toolName: String,
                output: String,
                serverId: String? = null,
                serverName: String? = null
            ): Result {
                return Result(
                    serverId = serverId,
                    serverName = serverName,
                    toolName = toolName,
                    success = false,
                    output = output
                )
            }
        }
    }

    override suspend fun doExecute(args: Args): Result {
        val contextService = project.service<AgentMcpContextService>()
        val context = contextService.get(sessionId)
            ?: return args.error("No MCP context found for current agent session")
        val conversationId = context.conversationId
            ?: return args.error("No MCP conversation is bound to the current session")
        if (!context.hasSelection()) {
            return args.error("No MCP servers selected. Add MCP server tags in the input panel.")
        }

        val selectedAttachments = loadAttachments(conversationId)
            .filter { it.serverId in context.selectedServerIds }
        val target = resolveTargetAttachment(args, context, selectedAttachments)
            ?: return args.error("Unable to resolve target MCP server for tool '${args.toolName}'")

        val attached = runCatching {
            ensureAttached(conversationId, target.serverId)
        }.getOrElse { error ->
            return args.error(
                output = "Failed to attach MCP server '${target.serverName}': " +
                        (error.message ?: "unknown error"),
                serverId = target.serverId,
                serverName = target.serverName
            )
        }
        if (!attached.isConnected()) {
            val error = attached.lastError ?: "MCP server is not connected"
            return args.error(
                output = "Failed to connect MCP server '${attached.serverName}': $error",
                serverId = attached.serverId,
                serverName = attached.serverName
            )
        }

        val resolvedToolName = resolveToolName(attached, args.toolName)
            ?: return args.error(
                output = "Tool '${args.toolName}' is not available on MCP server '${attached.serverName}'",
                serverId = attached.serverId,
                serverName = attached.serverName
            )

        val client = resolveClient(conversationId, attached.serverId)
            ?: return args.error(
                output = "MCP server '${attached.serverName}' is not connected",
                serverId = attached.serverId,
                serverName = attached.serverName
            )

        return executeTool(client, attached, args, resolvedToolName)
    }

    override fun createDeniedResult(originalArgs: Args, deniedReason: String): Result {
        return Result.error(
            toolName = originalArgs.toolName,
            output = deniedReason,
            serverId = originalArgs.serverId,
            serverName = originalArgs.serverName
        )
    }

    override fun encodeResultToString(result: Result): String {
        val status = if (result.success) "success" else "error"
        val server = result.serverName ?: result.serverId ?: "unknown"
        return buildString {
            appendLine("Tool: ${result.toolName}")
            appendLine("Server: $server")
            appendLine("Status: $status")
            appendLine("Output:")
            append(result.output)
        }
    }

    private fun loadAttachments(conversationId: UUID): List<McpSessionAttachment> {
        return runCatching { sessionManager.getSessionAttachments(conversationId) }
            .getOrDefault(emptyList())
    }

    private fun resolveTargetAttachment(
        args: Args,
        context: AgentMcpContext,
        selectedAttachments: List<McpSessionAttachment>
    ): McpSessionAttachment? {
        if (args.serverId.hasText()) {
            return resolveByServerId(args.serverId, context.selectedServerIds, selectedAttachments)
        }

        if (args.serverName.hasText()) {
            return resolveByServerName(args.serverName, selectedAttachments)
        }

        return resolveByToolOrFallback(
            toolName = args.toolName,
            selectedServerIds = context.selectedServerIds,
            selectedAttachments = selectedAttachments
        )
    }

    private fun resolveByServerId(
        serverId: String?,
        selectedServerIds: Set<String>,
        selectedAttachments: List<McpSessionAttachment>
    ): McpSessionAttachment? {
        val requestedId = serverId.normalizedOrNull() ?: return null
        if (requestedId !in selectedServerIds) {
            return null
        }
        return selectedAttachments.firstOrNull { it.serverId == requestedId }
            ?: disconnectedAttachment(requestedId)
    }

    private fun resolveByServerName(
        serverName: String?,
        selectedAttachments: List<McpSessionAttachment>
    ): McpSessionAttachment? {
        val requestedName = serverName.normalizedOrNull() ?: return null
        return selectedAttachments.firstOrNull {
            it.serverName.equals(requestedName, ignoreCase = true)
        }
    }

    private fun resolveByToolOrFallback(
        toolName: String,
        selectedServerIds: Set<String>,
        selectedAttachments: List<McpSessionAttachment>
    ): McpSessionAttachment? {
        if (selectedAttachments.isEmpty()) {
            return if (selectedServerIds.size == 1) disconnectedAttachment(selectedServerIds.first())
            else null
        }

        val byTool = selectedAttachments.filter { attachment ->
            attachment.availableTools.any { it.name.equals(toolName, ignoreCase = true) }
        }
        return when {
            byTool.size == 1 -> byTool.first()
            selectedAttachments.size == 1 -> selectedAttachments.first()
            else -> null
        }
    }

    private fun disconnectedAttachment(serverId: String): McpSessionAttachment {
        return McpSessionAttachment(
            serverId = serverId,
            serverName = serverId,
            connectionStatus = ConnectionStatus.DISCONNECTED
        )
    }

    private fun ensureAttached(conversationId: UUID, serverId: String): McpSessionAttachment {
        val current = loadAttachments(conversationId).firstOrNull { it.serverId == serverId }
        if (current != null && current.isConnected()) {
            return current
        }
        return sessionManager.attachServerToSession(conversationId, serverId).get()
    }

    private fun resolveToolName(attachment: McpSessionAttachment, requestedTool: String): String? {
        return attachment.availableTools
            .firstOrNull { it.name.equals(requestedTool, ignoreCase = true) }
            ?.name
    }

    private fun resolveClient(conversationId: UUID, serverId: String): McpSyncClient? {
        val clientKey = "$conversationId:$serverId"
        return runCatching { sessionManager.ensureClientConnected(clientKey).get() }.getOrNull()
    }

    private fun executeTool(
        client: McpSyncClient,
        attachment: McpSessionAttachment,
        args: Args,
        resolvedToolName: String
    ): Result {
        return runCatching {
            val callArgs = args.arguments.toMcpArguments()
            val request = McpSchema.CallToolRequest(resolvedToolName, callArgs)
            val toolResult = client.callTool(request)
            val content = toolResult.formatMcpContent()
            if (toolResult.isError == true) {
                Result.error(
                    toolName = resolvedToolName,
                    output = content,
                    serverId = attachment.serverId,
                    serverName = attachment.serverName
                )
            } else {
                Result(
                    serverId = attachment.serverId,
                    serverName = attachment.serverName,
                    toolName = resolvedToolName,
                    success = true,
                    output = content
                )
            }
        }.getOrElse { error ->
            Result.error(
                toolName = resolvedToolName,
                output = error.message ?: "MCP tool execution failed",
                serverId = attachment.serverId,
                serverName = attachment.serverName
            )
        }
    }

    private fun Args.error(
        output: String,
        serverId: String? = null,
        serverName: String? = null
    ): Result = Result.error(toolName, output, serverId, serverName)
}

private fun String?.hasText(): Boolean = this?.trim()?.isNotEmpty() == true

private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun buildToolDescription(
    context: AgentMcpContext?,
    sessionManager: McpSessionManager
): String {
    val base = """
Execute a tool on an attached MCP server.
- Use tool_name and arguments for the target MCP tool call.
- Use server_id when multiple selected servers expose the same tool.
""".trimIndent()
    val conversationId = context?.conversationId ?: return base
    val selectedServers = context.selectedServerIds
    if (selectedServers.isEmpty()) return base

    val attachments = runCatching { sessionManager.getSessionAttachments(conversationId) }
        .getOrDefault(emptyList())
        .filter { it.serverId in selectedServers }
    if (attachments.isEmpty()) return base

    val availableLines = attachments.map { attachment ->
        val tools = attachment.availableTools.joinToString(", ") { it.name }
        if (tools.isBlank()) {
            "- ${attachment.serverName} (${attachment.serverId}): no tools discovered yet"
        } else {
            "- ${attachment.serverName} (${attachment.serverId}): $tools"
        }
    }

    return buildString {
        appendLine(base)
        appendLine()
        appendLine("Selected MCP servers and tools:")
        availableLines.forEach { appendLine(it) }
    }.trim()
}
