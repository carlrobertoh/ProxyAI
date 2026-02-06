package ee.carlrobert.codegpt.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.completions.ToolApprovalMode
import ee.carlrobert.codegpt.toolwindow.ui.mcp.McpApprovalPanel
import ee.carlrobert.codegpt.toolwindow.ui.mcp.McpStatusPanel
import ee.carlrobert.llm.client.openai.completion.response.ToolCall
import ee.carlrobert.llm.client.openai.completion.response.ToolFunctionResponse
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
class McpToolCallHandler(private val project: Project) {

    private val sessionManager = service<McpSessionManager>()
    private val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val statusPanels = ConcurrentHashMap<String, McpStatusPanel>()
    private val activeExecutions = ConcurrentHashMap<String, CompletableFuture<*>>()

    fun executeToolCall(
        toolCall: McpToolCall,
        serverId: String,
        conversationId: UUID,
        approvalMode: ToolApprovalMode,
        onUIUpdate: (JPanel) -> Unit
    ): CompletableFuture<String> {
        return when (approvalMode) {
            ToolApprovalMode.AUTO_APPROVE -> {
                executeToolInternal(toolCall, serverId, conversationId, onUIUpdate)
            }

            ToolApprovalMode.REQUIRE_APPROVAL -> {
                requestUserApproval(toolCall, serverId, conversationId, onUIUpdate)
            }

            ToolApprovalMode.BLOCK_ALL -> {
                CompletableFuture.completedFuture("Tool execution blocked by policy")
            }
        }
    }

    private fun requestUserApproval(
        mcpToolCall: McpToolCall,
        serverId: String,
        conversationId: UUID,
        onUIUpdate: (JPanel) -> Unit
    ): CompletableFuture<String> {
        val toolCall = ToolCall(
            null,
            mcpToolCall.id,
            "function",
            ToolFunctionResponse(
                mcpToolCall.name,
                ObjectMapper().writeValueAsString(mcpToolCall.arguments)
            )
        )

        val approvalFuture = CompletableFuture<Boolean>()
        pendingApprovals[toolCall.id] = approvalFuture

        runInEdt {
            val actualServerName = try {
                sessionManager.getServerInfo(conversationId, serverId) ?: serverId
            } catch (_: Exception) {
                serverId
            }

            val approvalPanel = McpApprovalPanel(
                toolCall = toolCall,
                serverName = actualServerName,
                onApprove = { autoApprove ->
                    approvalFuture.complete(true)
                },
                onReject = {
                    approvalFuture.complete(false)
                },
            )
            onUIUpdate(approvalPanel)
        }

        return approvalFuture.thenCompose { approved ->
            pendingApprovals.remove(toolCall.id)

            if (approved) {
                executeToolInternal(mcpToolCall, serverId, conversationId, onUIUpdate)
            } else {
                CompletableFuture.completedFuture("Tool execution rejected by user")
            }
        }
    }

    private fun executeToolInternal(
        toolCall: McpToolCall,
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
            val statusPanel = McpStatusPanel(
                toolName = toolCall.name,
                serverName = actualServerName,
                initialStatus = "Executing...",
                args = toolCall.arguments,
            )
            statusPanels[toolCall.id] = statusPanel
            onUIUpdate(statusPanel)
        }

        val execution = CompletableFuture.supplyAsync {
            try {
                val clientKey = "$conversationId:$serverId"
                val mcpClient = sessionManager.ensureClientConnected(clientKey).get()
                if (mcpClient == null) {
                    return@supplyAsync "Error: MCP server '$actualServerName' not connected"
                }

                executeWithClient(mcpClient, toolCall)
            } catch (e: Exception) {
                logger.error("Exception in executeToolInternal: ${e.message}", e)
                "Error executing tool: ${e.message}"
            }
        }
        activeExecutions[toolCall.id] = execution
        execution.whenComplete { _, _ -> activeExecutions.remove(toolCall.id) }
        return execution
    }

    private fun executeWithClient(
        client: McpSyncClient,
        toolCall: McpToolCall,
    ): String {
        return try {
            val validatedArguments = when {
                toolCall.arguments.isEmpty() -> {
                    emptyMap<String, Any>()
                }

                else -> {
                    toolCall.arguments
                }
            }
            val callToolRequest = McpSchema.CallToolRequest(toolCall.name, validatedArguments)
            val toolResult = try {
                val future = CompletableFuture.supplyAsync {
                    client.callTool(callToolRequest)
                }
                future.get(30, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                val message = "Tool call '${toolCall.name}' timed out after 30 seconds"
                logger.error(message, e)
                return message
            } catch (e: Exception) {
                val message = "Tool call '${toolCall.name}' failed: ${e.message}"
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
                        panel.complete(false, null, errorMessage)
                    }
                    statusPanels.remove(toolCall.id)
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
            val resultContent = rawResultContent

            statusPanels[toolCall.id]?.let { panel ->
                runInEdt {
                    val displayMessage = formatSuccessMessage(resultContent)
                    panel.complete(true, displayMessage, resultContent)
                }
                statusPanels.remove(toolCall.id)
            }

            resultContent
        } catch (e: Exception) {
            val errorMessage = "Tool execution error: ${e.message ?: "Unknown error"}"
            logger.error("Tool execution failed for '${toolCall.name}': $errorMessage", e)

            val formattedError = formatForStatusPanel(errorMessage)
            statusPanels[toolCall.id]?.let { panel ->
                runInEdt {
                    panel.complete(false, null, formattedError)
                }
                statusPanels.remove(toolCall.id)
            }

            errorMessage
        }
    }

    fun cancelAllPendingApprovals() {
        pendingApprovals.values.forEach { it.complete(false) }
        pendingApprovals.clear()
    }

    fun cancelAllExecutions() {
        activeExecutions.values.forEach { it.cancel(true) }
        activeExecutions.clear()
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

    private fun formatSuccessMessage(result: String): String {
        val cleaned = result.trim()

        return when {
            cleaned.contains("search results") || cleaned.contains("results found") -> {
                val countMatch = Regex("(\\d+)\\s*(?:search\\s*)?results?").find(cleaned.lowercase())
                if (countMatch != null) {
                    "Found ${countMatch.groupValues[1]} results"
                } else {
                    "Search completed successfully"
                }
            }

            cleaned.contains("SELECT") || cleaned.contains("INSERT") || cleaned.contains("UPDATE") -> {
                val rowMatch = Regex("(\\d+)\\s*rows?").find(cleaned)
                if (rowMatch != null) {
                    "Query returned ${rowMatch.groupValues[1]} rows"
                } else {
                    "Query completed successfully"
                }
            }

            cleaned.contains("file") && (cleaned.contains("created") || cleaned.contains("updated") || cleaned.contains("deleted")) -> {
                when {
                    cleaned.contains("created") -> "File created successfully"
                    cleaned.contains("updated") -> "File updated successfully"
                    cleaned.contains("deleted") -> "File deleted successfully"
                    else -> "File operation completed"
                }
            }

            cleaned.contains("directory") && (cleaned.contains("created") || cleaned.contains("deleted")) -> {
                when {
                    cleaned.contains("created") -> "Directory created successfully"
                    cleaned.contains("deleted") -> "Directory deleted successfully"
                    else -> "Directory operation completed"
                }
            }

            cleaned.isBlank() -> "Completed successfully"
            cleaned.length > 100 -> "Completed successfully"
            else -> "Completed successfully"
        }
    }
}
