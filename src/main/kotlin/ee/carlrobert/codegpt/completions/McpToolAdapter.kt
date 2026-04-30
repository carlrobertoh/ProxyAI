package ee.carlrobert.codegpt.completions

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.typeToken
import ee.carlrobert.codegpt.mcp.McpTool
import ee.carlrobert.codegpt.mcp.McpToolCallHandler
import ee.carlrobert.codegpt.mcp.ToolSchemaParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.*
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
    argsType = typeToken<JsonObject>(),
    resultType = typeToken<String>(),
    descriptor = buildDescriptor(exposedName, tool)
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

private fun buildDescriptor(exposedName: String, tool: McpTool): ToolDescriptor {
    val description = tool.description.ifBlank { "MCP tool" }
    val schema = tool.schema as Map<String, Any?>
    val allParameters = ToolSchemaParser.parseParameterDescriptors(schema)
    val requiredNames = ToolSchemaParser.parseRequiredNames(schema).toSet()
    val required = allParameters.filter { it.name in requiredNames }
    val optional = allParameters.filterNot { it.name in requiredNames }
    return ToolDescriptor(
        name = exposedName,
        description = description,
        requiredParameters = required,
        optionalParameters = optional
    )
}
