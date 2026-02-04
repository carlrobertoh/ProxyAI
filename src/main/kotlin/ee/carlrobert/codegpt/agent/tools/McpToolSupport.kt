package ee.carlrobert.codegpt.agent.tools

import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.ImageContent
import io.modelcontextprotocol.spec.McpSchema.TextContent
import kotlinx.serialization.json.*

internal fun Map<String, JsonElement>.toMcpArguments(): Map<String, Any> {
    return mapNotNull { (key, value) ->
        value.toMcpArgumentValue()?.let { key to it }
    }.toMap()
}

internal fun McpSchema.CallToolResult.formatMcpContent(): String {
    val content = content ?: emptyList()
    if (content.isEmpty()) {
        return "Tool executed successfully (no content returned)"
    }
    return content.joinToString("\n") { item ->
        when (item) {
            is TextContent -> item.text
            is ImageContent -> "[image]"
            else -> item.toString()
        }
    }
}

internal fun buildMcpApprovalDetails(
    serverId: String?,
    serverName: String?,
    toolName: String,
    arguments: Any
): String {
    return buildString {
        appendLine("ServerId: ${serverId ?: "<auto>"}")
        appendLine("ServerName: ${serverName ?: "<auto>"}")
        appendLine("Tool: $toolName")
        append("Arguments: $arguments")
    }
}

private fun JsonElement.toMcpArgumentValue(): Any? {
    return when (this) {
        JsonNull -> null
        is JsonObject -> mapNotNull { (key, value) ->
            value.toMcpArgumentValue()?.let { key to it }
        }.toMap()

        is JsonArray -> mapNotNull { it.toMcpArgumentValue() }
        is JsonPrimitive -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
    }
}
