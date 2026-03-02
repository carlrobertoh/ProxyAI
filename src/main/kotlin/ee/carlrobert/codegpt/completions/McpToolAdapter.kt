package ee.carlrobert.codegpt.completions

import ai.koog.agents.core.tools.Tool
import ee.carlrobert.codegpt.mcp.McpTool
import ee.carlrobert.codegpt.mcp.McpToolCallHandler
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.swing.JPanel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class McpToolAdapter(
    private val tool: McpTool,
    private val exposedName: String,
    private val callParameters: ChatCompletionParameters,
    private val toolCallHandler: McpToolCallHandler,
    private val onToolCallUIUpdate: (JPanel) -> Unit
) : Tool<JsonObject, String>(
    argsSerializer = JsonObject.serializer(),
    resultSerializer = String.serializer(),
    name = exposedName,
    description = tool.description.ifBlank { "MCP tool" }
) {
    override suspend fun execute(args: JsonObject): String {
        val callId = UUID.randomUUID().toString()
        val toolCall = ChatToolCall(
            id = callId,
            function = ChatToolFunction(
                name = tool.name,
                arguments = Json.encodeToString(JsonObject.serializer(), args)
            )
        )
        callParameters.message.addToolCall(toolCall)
        val result = awaitResult(
            toolCallHandler.executeToolCall(
                toolCall = toolCall,
                serverId = tool.serverId,
                conversationId = callParameters.conversation.id,
                approvalMode = callParameters.toolApprovalMode,
                onUIUpdate = onToolCallUIUpdate
            )
        )
        callParameters.message.addToolCallResult(callId, result)
        return result
    }

    private suspend fun awaitResult(future: CompletableFuture<String>): String =
        suspendCancellableCoroutine { continuation ->
            future.whenComplete { value, throwable ->
                if (throwable != null) {
                    continuation.resumeWithException(throwable)
                } else {
                    continuation.resume(value)
                }
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }
}
