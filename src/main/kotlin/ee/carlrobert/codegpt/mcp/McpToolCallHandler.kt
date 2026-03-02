package ee.carlrobert.codegpt.mcp

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.tools.McpTool as AgentMcpTool
import ee.carlrobert.codegpt.completions.ChatToolCall
import ee.carlrobert.codegpt.completions.ToolApprovalMode
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.DefaultApprovalPanelFactory
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalRequest
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalType
import ee.carlrobert.codegpt.toolwindow.agent.ui.ToolCallCard
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolKind
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.ImageContent
import io.modelcontextprotocol.spec.McpSchema.TextContent
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.JPanel

/**
 * Handles MCP tool call execution with user approval workflow.
 * Manages the lifecycle of tool calls from request to result display.
 */
@Service(Service.Level.PROJECT)
class McpToolCallHandler(
    private val project: Project
) {

    private val sessionManager = service<McpSessionManager>()
    private val statusPanels = ConcurrentHashMap<String, ToolCallCard>()
    private val statusPanelConversationIds = ConcurrentHashMap<String, UUID>()
    private val activeExecutions = ConcurrentHashMap<String, CompletableFuture<*>>()
    private val activeExecutionConversationIds = ConcurrentHashMap<String, UUID>()
    private val autoApprovedConversations = ConcurrentHashMap.newKeySet<UUID>()
    private val approvalQueues = ConcurrentHashMap<UUID, ArrayDeque<PendingApprovalRequest>>()
    private val activeApprovals = ConcurrentHashMap<UUID, PendingApprovalRequest>()
    private val queueLock = Any()

    private data class PendingApprovalRequest(
        val toolCall: ChatToolCall,
        val parsedArguments: Map<String, Any?>,
        val serverId: String,
        val conversationId: UUID,
        val onUIUpdate: (JPanel) -> Unit,
        val resultFuture: CompletableFuture<String>
    )

    fun executeToolCall(
        toolCall: ChatToolCall,
        serverId: String,
        conversationId: UUID,
        approvalMode: ToolApprovalMode,
        onUIUpdate: (JPanel) -> Unit
    ): CompletableFuture<String> {
        val parseResult = McpToolArgumentsParser.parse(toolCall.function.arguments)
        if (parseResult.error != null) {
            return CompletableFuture.completedFuture(
                "Tool execution blocked: ${parseResult.error}"
            )
        }
        val parsedArguments = parseResult.arguments
        return when (approvalMode) {
            ToolApprovalMode.AUTO_APPROVE -> {
                executeToolInternal(toolCall, parsedArguments, serverId, conversationId, onUIUpdate)
            }

            ToolApprovalMode.REQUIRE_APPROVAL -> {
                requestUserApproval(
                    toolCall,
                    parsedArguments,
                    serverId,
                    conversationId,
                    onUIUpdate
                )
            }

            ToolApprovalMode.BLOCK_ALL -> {
                CompletableFuture.completedFuture("Tool execution blocked by policy")
            }
        }
    }

    private fun requestUserApproval(
        toolCall: ChatToolCall,
        parsedArguments: Map<String, Any?>,
        serverId: String,
        conversationId: UUID,
        onUIUpdate: (JPanel) -> Unit
    ): CompletableFuture<String> {
        if (autoApprovedConversations.contains(conversationId)) {
            return executeToolInternal(toolCall, parsedArguments, serverId, conversationId, onUIUpdate)
        }

        val resultFuture = CompletableFuture<String>()
        val request = PendingApprovalRequest(
            toolCall = toolCall,
            parsedArguments = parsedArguments,
            serverId = serverId,
            conversationId = conversationId,
            onUIUpdate = onUIUpdate,
            resultFuture = resultFuture
        )

        synchronized(queueLock) {
            approvalQueues.computeIfAbsent(conversationId) { ArrayDeque() }
                .addLast(request)
        }

        maybeShowNextApproval(conversationId)
        return resultFuture
    }

    private fun maybeShowNextApproval(conversationId: UUID) {
        val next = synchronized(queueLock) {
            if (activeApprovals.containsKey(conversationId)) {
                return
            }
            val queue = approvalQueues[conversationId] ?: return
            val request = queue.pollFirst() ?: run {
                approvalQueues.remove(conversationId)
                return
            }
            activeApprovals[conversationId] = request
            request
        }

        runInEdt {
            val actualServerName = try {
                sessionManager.getServerInfo(next.conversationId, next.serverId) ?: next.serverId
            } catch (_: Exception) {
                next.serverId
            }

            if (autoApprovedConversations.contains(next.conversationId)) {
                resolveApproval(next, approved = true, autoApproveSession = true)
                return@runInEdt
            }

            val approvalPanel = DefaultApprovalPanelFactory.create(
                project = project,
                request = ToolApprovalRequest(
                    type = ToolApprovalType.GENERIC,
                    title = "Run MCP tool '${next.toolCall.function.name ?: "unknown-function"}'",
                    details = buildApprovalDetails(actualServerName, next.parsedArguments)
                ),
                onApprove = { autoApprove ->
                    resolveApproval(next, approved = true, autoApproveSession = autoApprove)
                },
                onReject = {
                    resolveApproval(next, approved = false)
                }
            )
            next.onUIUpdate(approvalPanel)
        }
    }

    private fun resolveApproval(
        request: PendingApprovalRequest,
        approved: Boolean,
        autoApproveSession: Boolean = false
    ) {
        val removed = synchronized(queueLock) {
            if (activeApprovals[request.conversationId] != request) {
                false
            } else {
                activeApprovals.remove(request.conversationId)
                true
            }
        }
        if (!removed) {
            return
        }

        if (autoApproveSession) {
            autoApprovedConversations.add(request.conversationId)
        }

        if (!approved) {
            request.resultFuture.complete("Tool execution rejected by user")
            maybeShowNextApproval(request.conversationId)
            return
        }

        executeToolInternal(
            toolCall = request.toolCall,
            parsedArguments = request.parsedArguments,
            serverId = request.serverId,
            conversationId = request.conversationId,
            onUIUpdate = request.onUIUpdate
        ).whenComplete { result, throwable ->
            if (throwable != null) {
                request.resultFuture.complete("Error executing tool: ${throwable.message}")
            } else {
                request.resultFuture.complete(result)
            }
        }

        maybeShowNextApproval(request.conversationId)
    }

    private fun executeToolInternal(
        toolCall: ChatToolCall,
        parsedArguments: Map<String, Any?>,
        serverId: String,
        conversationId: UUID,
        onUIUpdate: (JPanel) -> Unit
    ): CompletableFuture<String> {
        val actualServerName = try {
            sessionManager.getServerInfo(conversationId, serverId) ?: serverId
        } catch (_: Exception) {
            serverId
        }

        runInEdt {
            val statusPanel = ToolCallCard(
                project = project,
                toolName = toolCall.function.name ?: "MCP",
                args = Unit,
                overrideKind = ToolKind.MCP
            )
            statusPanels[toolCall.id] = statusPanel
            statusPanelConversationIds[toolCall.id] = conversationId
            onUIUpdate(statusPanel)
        }

        val execution = CompletableFuture.supplyAsync {
            try {
                val clientKey = "$conversationId:$serverId"
                val mcpClient = sessionManager.ensureClientConnected(clientKey).get()
                    ?: return@supplyAsync "Error: MCP server '$actualServerName' not connected"

                executeWithClient(
                    client = mcpClient,
                    toolCall = toolCall,
                    parsedArguments = parsedArguments,
                    serverId = serverId,
                    serverName = actualServerName
                )
            } catch (e: Exception) {
                logger.error("Exception in executeToolInternal: ${e.message}", e)
                "Error executing tool: ${e.message}"
            }
        }
        activeExecutions[toolCall.id] = execution
        activeExecutionConversationIds[toolCall.id] = conversationId
        execution.whenComplete { _, _ ->
            activeExecutions.remove(toolCall.id)
            activeExecutionConversationIds.remove(toolCall.id)
        }
        return execution
    }

    private fun executeWithClient(
        client: McpSyncClient,
        toolCall: ChatToolCall,
        parsedArguments: Map<String, Any?>,
        serverId: String,
        serverName: String,
    ): String {
        return try {
            val toolName = toolCall.function.name
                ?: return "Tool execution error: missing tool name"
            @Suppress("UNCHECKED_CAST")
            val callToolRequest = McpSchema.CallToolRequest(
                toolName,
                parsedArguments as Map<String, Any>
            )
            val toolResult = try {
                val future = CompletableFuture.supplyAsync {
                    client.callTool(callToolRequest)
                }
                future.get(30, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                val message = "Tool call '$toolName' timed out after 30 seconds"
                logger.error(message, e)
                return message
            } catch (e: Exception) {
                val message = "Tool call '$toolName' failed: ${e.message}"
                logger.error(message, e)
                return message
            }

            if (toolResult.isError == true) {
                val errorMessage = when (val content = toolResult.content) {
                    is List<*> -> {
                        content.firstOrNull()?.let { item ->
                            when (item) {
                                is TextContent -> item.text
                                else -> item.toString()
                            }
                        } ?: "Unknown error"
                    }

                    else -> content?.toString() ?: "Unknown error"
                }

                statusPanels[toolCall.id]?.let { panel ->
                    runInEdt {
                        panel.complete(
                            false,
                            AgentMcpTool.Result(
                                serverId = serverId,
                                serverName = serverName,
                                toolName = toolName,
                                success = false,
                                output = errorMessage
                            )
                        )
                    }
                    statusPanels.remove(toolCall.id)
                    statusPanelConversationIds.remove(toolCall.id)
                }

                return "Tool execution failed: $errorMessage"
            }

            val rawResultContent = when {
                toolResult.content != null -> {
                    try {
                        when (val content = toolResult.content) {
                            is List<*> -> {
                                content.joinToString("\n") { item ->
                                    when (item) {
                                        is TextContent -> item.text
                                        is ImageContent -> "[Image content]"
                                        else -> item.toString()
                                    }
                                }
                            }

                            else -> content.toString()
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to process tool result content: ${e.message}", e)
                        toolResult.content.toString()
                    }
                }

                toolResult.isError -> "Error: Tool execution failed"
                else -> "Tool executed successfully (no content returned)"
            }
            statusPanels[toolCall.id]?.let { panel ->
                runInEdt {
                    panel.complete(
                        true,
                        AgentMcpTool.Result(
                            serverId = serverId,
                            serverName = serverName,
                            toolName = toolName,
                            success = true,
                            output = rawResultContent
                        )
                    )
                }
                statusPanels.remove(toolCall.id)
                statusPanelConversationIds.remove(toolCall.id)
            }

            rawResultContent
        } catch (e: Exception) {
            val errorMessage = "Tool execution error: ${e.message ?: "Unknown error"}"
            logger.error("Tool execution failed for '${toolCall.function.name}': $errorMessage", e)

            val formattedError = formatForStatusPanel(errorMessage)
            statusPanels[toolCall.id]?.let { panel ->
                runInEdt {
                    panel.complete(
                        false,
                        AgentMcpTool.Result(
                            serverId = serverId,
                            serverName = serverName,
                            toolName = toolCall.function.name ?: "MCP",
                            success = false,
                            output = formattedError
                        )
                    )
                }
                statusPanels.remove(toolCall.id)
                statusPanelConversationIds.remove(toolCall.id)
            }

            errorMessage
        }
    }

    fun cancelPendingApprovals(conversationId: UUID) {
        val toCancel = mutableListOf<PendingApprovalRequest>()
        synchronized(queueLock) {
            activeApprovals.remove(conversationId)?.let(toCancel::add)

            approvalQueues.remove(conversationId)?.let { queue ->
                while (queue.isNotEmpty()) {
                    toCancel.add(queue.removeFirst())
                }
            }
        }

        toCancel.forEach { request ->
            request.resultFuture.complete("Tool execution rejected by user")
        }
    }

    fun cancelExecutions(conversationId: UUID) {
        val executionIds = activeExecutionConversationIds
            .filterValues { it == conversationId }
            .keys
            .toList()
        executionIds.forEach { callId ->
            activeExecutions.remove(callId)?.cancel(true)
            activeExecutionConversationIds.remove(callId)
        }

        val statusIds = statusPanelConversationIds
            .filterValues { it == conversationId }
            .keys
            .toList()
        statusIds.forEach { callId ->
            val panel = statusPanels.remove(callId) ?: return@forEach
            statusPanelConversationIds.remove(callId)
            runInEdt {
                panel.complete(
                    false,
                    AgentMcpTool.Result.error(
                        toolName = "MCP",
                        output = "Cancelled"
                    )
                )
            }
        }
    }

    fun clearConversationState(conversationId: UUID) {
        autoApprovedConversations.remove(conversationId)
        synchronized(queueLock) {
            activeApprovals.remove(conversationId)
            approvalQueues.remove(conversationId)
        }
    }

    companion object {
        private val logger = thisLogger()

        @JvmStatic
        fun getInstance(project: Project): McpToolCallHandler {
            return project.service<McpToolCallHandler>()
        }
    }

    private fun formatForStatusPanel(raw: String): String {
        return if (raw.length > 100) raw.take(100) + "..." else raw
    }

    private fun buildApprovalDetails(serverName: String, args: Map<String, Any?>): String {
        val arguments = if (args.isEmpty()) {
            "Arguments: none"
        } else {
            buildString {
                append("Arguments:\n")
                args.entries.forEachIndexed { index, (key, value) ->
                    val suffix = if (index < args.size - 1) "\n" else ""
                    append("- ")
                    append(key)
                    append(": ")
                    append(value)
                    append(suffix)
                }
            }
        }

        return "Server: $serverName\n$arguments"
    }
}
