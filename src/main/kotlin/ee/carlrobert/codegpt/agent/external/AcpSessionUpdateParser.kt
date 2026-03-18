package ee.carlrobert.codegpt.agent.external

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal sealed interface AcpSessionUpdate {
    data class TextChunk(val text: String) : AcpSessionUpdate
    data class ThoughtChunk(val text: String) : AcpSessionUpdate
    data class ToolCall(
        val toolCall: AcpDecodedToolCall,
        val status: AcpToolCallStatus?,
        val rawOutput: JsonElement?
    ) : AcpSessionUpdate

    data class ToolCallUpdate(
        val toolCallId: String,
        val toolCall: AcpDecodedToolCall?,
        val status: AcpToolCallStatus,
        val rawOutput: JsonElement?
    ) : AcpSessionUpdate

    data class ConfigOptionUpdate(val update: JsonObject) : AcpSessionUpdate
}

internal enum class AcpToolCallStatus(val wireValue: String) {
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    val isTerminal: Boolean
        get() = this != IN_PROGRESS

    companion object {
        fun fromWireValue(value: String?): AcpToolCallStatus? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

internal class AcpSessionUpdateParser(
    private val toolCallDecoder: AcpToolCallDecoder
) {

    fun parse(notification: AcpJsonRpcNotification): AcpSessionUpdate? {
        if (notification.method != SESSION_UPDATE_METHOD) {
            return null
        }

        val update = notification.params["update"] as? JsonObject ?: return null
        return when (SessionUpdateKind.fromWireValue(update.string("sessionUpdate"))) {
            SessionUpdateKind.AGENT_MESSAGE_CHUNK -> parseAgentMessageChunk(update)
            SessionUpdateKind.TOOL_CALL -> parseToolCall(update)
            SessionUpdateKind.TOOL_CALL_UPDATE -> parseToolCallUpdate(update)
            SessionUpdateKind.CONFIG_OPTION_UPDATE -> AcpSessionUpdate.ConfigOptionUpdate(update)
            null -> null
        }
    }

    private fun parseAgentMessageChunk(update: JsonObject): AcpSessionUpdate? {
        val content = update["content"] as? JsonObject ?: return null
        return when (MessageChunkType.fromWireValue(content.string("type"))) {
            MessageChunkType.TEXT ->
                AcpSessionUpdate.TextChunk(content.string("text").orEmpty())

            MessageChunkType.THOUGHT -> {
                val text = content.string("thought").orEmpty()
                text.takeIf { it.isNotBlank() }?.let(AcpSessionUpdate::ThoughtChunk)
            }

            null -> null
        }
    }

    private fun parseToolCall(update: JsonObject): AcpSessionUpdate? {
        val toolCall = toolCallDecoder.decodeToolCall(update) ?: return null
        return AcpSessionUpdate.ToolCall(
            toolCall = toolCall,
            status = AcpToolCallStatus.fromWireValue(update.string("status")),
            rawOutput = update["rawOutput"] ?: update["content"]
        )
    }

    private fun parseToolCallUpdate(update: JsonObject): AcpSessionUpdate? {
        val toolCallId = update.string("toolCallId") ?: return null
        val status = AcpToolCallStatus.fromWireValue(update.string("status")) ?: return null
        return AcpSessionUpdate.ToolCallUpdate(
            toolCallId = toolCallId,
            toolCall = toolCallDecoder.decodeToolCall(update),
            status = status,
            rawOutput = update["rawOutput"] ?: update["content"]
        )
    }

    private enum class SessionUpdateKind(val wireValue: String) {
        AGENT_MESSAGE_CHUNK("agent_message_chunk"),
        TOOL_CALL("tool_call"),
        TOOL_CALL_UPDATE("tool_call_update"),
        CONFIG_OPTION_UPDATE("config_option_update");

        companion object {
            fun fromWireValue(value: String?): SessionUpdateKind? {
                return entries.firstOrNull { it.wireValue == value }
            }
        }
    }

    private enum class MessageChunkType(val wireValue: String) {
        TEXT("text"),
        THOUGHT("thought");

        companion object {
            fun fromWireValue(value: String?): MessageChunkType? {
                return entries.firstOrNull { it.wireValue == value }
            }
        }
    }

    private companion object {
        const val SESSION_UPDATE_METHOD = "session/update"
    }
}
