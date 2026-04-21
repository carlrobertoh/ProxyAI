package ee.carlrobert.codegpt.agent.external

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.RequestPermissionRequest
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolCallLocation
import com.agentclientprotocol.model.ToolKind
import ee.carlrobert.codegpt.agent.ToolSpecs
import ee.carlrobert.codegpt.agent.tools.EditTool
import ee.carlrobert.codegpt.agent.tools.McpTool
import ee.carlrobert.codegpt.agent.tools.WriteTool
import ee.carlrobert.codegpt.agent.external.events.AcpExternalEvent
import ee.carlrobert.codegpt.agent.external.events.AcpPermissionRequestSnapshot
import ee.carlrobert.codegpt.agent.external.events.AcpToolCallArgs
import ee.carlrobert.codegpt.agent.external.events.AcpToolCallSnapshot
import ee.carlrobert.codegpt.agent.external.events.toAcpToolCallContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.charset.StandardCharsets

internal class AcpToolCallDecoder(
    private val json: Json
) {
    private val support = AcpToolCallDecodingSupport(json)
    private val standardNormalizer = StandardSemanticToolCallNormalizer()
    private val zedNormalizer = ZedAdapterToolCallNormalizer()
    private val geminiNormalizer = GeminiCliToolCallNormalizer()
    private val fallbackNormalizer = FallbackToolCallNormalizer()

    fun decodeExternalEvent(
        flavor: AcpToolEventFlavor,
        update: SessionUpdate
    ): AcpExternalEvent? {
        return when (update) {
            is SessionUpdate.UserMessageChunk -> null
            is SessionUpdate.AgentMessageChunk -> {
                (update.content as? ContentBlock.Text)?.text?.let {
                    AcpExternalEvent.TextChunk(it)
                }
            }

            is SessionUpdate.AgentThoughtChunk -> {
                (update.content as? ContentBlock.Text)?.text?.let {
                    AcpExternalEvent.ThinkingChunk(it)
                }
            }

            is SessionUpdate.PlanUpdate -> AcpExternalEvent.PlanUpdate(update.entries)
            is SessionUpdate.UsageUpdate -> AcpExternalEvent.UsageUpdate(
                used = update.used,
                size = update.size,
                cost = update.cost
            )
            is SessionUpdate.ConfigOptionUpdate -> AcpExternalEvent.ConfigOptionUpdate(update.configOptions)
            is SessionUpdate.SessionInfoUpdate -> AcpExternalEvent.SessionInfoUpdate(
                title = update.title,
                updatedAt = update.updatedAt
            )
            is SessionUpdate.UnknownSessionUpdate -> AcpExternalEvent.UnknownSessionUpdate(
                type = update.sessionUpdateType,
                rawJson = update.rawJson
            )
            is SessionUpdate.AvailableCommandsUpdate -> AcpExternalEvent.AvailableCommandsUpdate(update.availableCommands)
            is SessionUpdate.CurrentModeUpdate -> AcpExternalEvent.CurrentModeUpdate(update.currentModeId.value)
            is SessionUpdate.ToolCall -> AcpExternalEvent.ToolCallStarted(decodeToolCallSnapshot(flavor, update))
            is SessionUpdate.ToolCallUpdate -> AcpExternalEvent.ToolCallUpdated(decodeToolCallSnapshot(flavor, update))
        }
    }

    fun decodePermissionRequest(
        flavor: AcpToolEventFlavor,
        request: RequestPermissionRequest
    ): AcpPermissionRequestSnapshot {
        return AcpPermissionRequestSnapshot(
            toolCall = decodeToolCallSnapshot(
                flavor = flavor,
                toolCallId = request.toolCall.toolCallId.value,
            rawTitle = request.toolCall.title ?: "Allow action?",
            rawKind = request.toolCall.kind?.wireValue,
            rawInput = request.toolCall.rawInput,
            locations = request.toolCall.locations.orEmpty(),
            content = request.toolCall.content.orEmpty(),
            rawMeta = request.toolCall._meta,
            defaultTitle = "Allow action?"
        ),
            details = permissionDetails(
                locations = request.toolCall.locations.orEmpty(),
                rawInput = request.toolCall.rawInput,
                fallback = request.toolCall.toString()
            ),
            options = request.options
        )
    }

    private fun decodeToolCallSnapshot(
        flavor: AcpToolEventFlavor,
        update: SessionUpdate.ToolCall
    ): AcpToolCallSnapshot {
        return decodeToolCallSnapshot(
            flavor = flavor,
            toolCallId = update.toolCallId.value,
            rawTitle = update.title,
            rawKind = update.kind?.wireValue,
            rawInput = update.rawInput,
            locations = update.locations,
            content = update.content,
            rawMeta = update._meta,
            rawOutput = update.rawOutput,
            status = update.status?.toAcpToolCallStatus()
        )
    }

    private fun decodeToolCallSnapshot(
        flavor: AcpToolEventFlavor,
        update: SessionUpdate.ToolCallUpdate
    ): AcpToolCallSnapshot {
        return decodeToolCallSnapshot(
            flavor = flavor,
            toolCallId = update.toolCallId.value,
            rawTitle = update.title.orEmpty(),
            rawKind = update.kind?.wireValue,
            rawInput = update.rawInput,
            locations = update.locations.orEmpty(),
            content = update.content.orEmpty(),
            rawMeta = update._meta,
            rawOutput = update.rawOutput,
            status = update.status?.toAcpToolCallStatus(),
            defaultTitle = ""
        )
    }

    private fun decodeToolCallSnapshot(
        flavor: AcpToolEventFlavor,
        toolCallId: String,
        rawTitle: String,
        rawKind: String?,
        rawInput: JsonElement?,
        locations: List<ToolCallLocation>,
        content: List<ToolCallContent>,
        rawMeta: JsonElement? = null,
        rawOutput: JsonElement? = null,
        status: AcpToolCallStatus? = null,
        defaultTitle: String = "Tool"
    ): AcpToolCallSnapshot {
        val resolved = resolveToolCall(
            flavor = flavor,
            context = AcpToolCallContext(
                toolCallId = toolCallId,
                rawTitle = rawTitle,
                rawKind = rawKind,
                rawInput = rawInput,
                locations = locations,
                content = content,
                defaultTitle = defaultTitle
            )
        )
        return AcpToolCallSnapshot(
            id = toolCallId,
            title = resolved.rawTitle,
            toolName = resolved.toolName,
            kind = rawKind?.toAcpToolKind(),
            status = status,
            args = resolved.typedArgs,
            locations = locations,
            content = content.toAcpToolCallContent(),
            meta = rawMeta,
            rawInput = rawInput,
            rawOutput = rawOutput
        )
    }

    fun decodeResult(
        toolName: String,
        args: AcpToolCallArgs?,
        status: AcpToolCallStatus,
        rawOutput: JsonElement?
    ): Any? {
        val payload = rawOutput.toPayloadString()
        ToolSpecs.decodeResultOrNull(toolName, payload)?.let { return it }

        if (status == AcpToolCallStatus.COMPLETED) {
            when (args) {
                is AcpToolCallArgs.Mcp -> return McpTool.Result(
                    serverId = args.value.serverId,
                    serverName = args.value.serverName,
                    toolName = args.value.toolName,
                    success = true,
                    output = payload.ifBlank { "MCP tool completed" }
                )

                is AcpToolCallArgs.Edit -> return EditTool.Result.Success(
                    filePath = args.value.filePath,
                    replacementsMade = 1,
                    message = "Edit completed"
                )

                is AcpToolCallArgs.Write -> return WriteTool.Result.Success(
                    filePath = args.value.filePath,
                    bytesWritten = args.value.content.toByteArray(StandardCharsets.UTF_8).size,
                    isNewFile = false,
                    message = "Write completed"
                )

                else -> Unit
            }
        }

        if (status == AcpToolCallStatus.FAILED || status == AcpToolCallStatus.CANCELLED) {
            val message = payload.ifBlank { "Tool ${status.wireValue}" }
            when (args) {
                is AcpToolCallArgs.Mcp -> return McpTool.Result.error(
                    toolName = args.value.toolName,
                    output = message,
                    serverId = args.value.serverId,
                    serverName = args.value.serverName
                )

                is AcpToolCallArgs.Edit -> return EditTool.Result.Error(args.value.filePath, message)
                is AcpToolCallArgs.Write -> return WriteTool.Result.Error(args.value.filePath, message)
                else -> Unit
            }
        }

        return payload.ifBlank { null }
    }

    fun decodeResult(
        toolCall: AcpToolCallSnapshot,
        rawOutput: JsonElement?
    ): Any? {
        return decodeResult(
            toolName = toolCall.toolName,
            args = toolCall.args,
            status = toolCall.status ?: AcpToolCallStatus.COMPLETED,
            rawOutput = rawOutput ?: toolCall.rawOutput
        )
    }

    private fun resolveToolCall(
        flavor: AcpToolEventFlavor,
        context: AcpToolCallContext
    ): AcpResolvedToolCall {
        val vendorNormalizers = when (flavor) {
            AcpToolEventFlavor.ZED_ADAPTER -> listOf(zedNormalizer, standardNormalizer)
            AcpToolEventFlavor.GEMINI_CLI -> listOf(geminiNormalizer, standardNormalizer)
            AcpToolEventFlavor.STANDARD -> listOf(standardNormalizer)
        }
        return vendorNormalizers
            .firstNotNullOfOrNull { it.normalize(context, support) }
            ?: fallbackNormalizer.normalize(context, support)
    }

    private fun permissionDetails(
        locations: List<ToolCallLocation>,
        rawInput: JsonElement?,
        fallback: String
    ): String {
        return buildString {
            locations.map(ToolCallLocation::path)
                .takeIf { it.isNotEmpty() }
                ?.let { paths ->
                    appendLine("Locations:")
                    paths.forEach(::appendLine)
                }
            rawInput?.let { input ->
                if (isNotBlank()) {
                    appendLine()
                }
                appendLine("Input:")
                append(input.toString())
            }
        }.ifBlank { fallback }
    }
}

private val ToolKind.wireValue: String
    get() = when (this) {
        ToolKind.READ -> "read"
        ToolKind.EDIT -> "edit"
        ToolKind.EXECUTE -> "execute"
        ToolKind.SEARCH -> "search"
        ToolKind.FETCH -> "fetch"
        else -> name.lowercase()
    }

private fun ToolCallStatus.toAcpToolCallStatus(): AcpToolCallStatus {
    return when (this) {
        ToolCallStatus.PENDING,
        ToolCallStatus.IN_PROGRESS -> AcpToolCallStatus.IN_PROGRESS

        ToolCallStatus.COMPLETED -> AcpToolCallStatus.COMPLETED
        ToolCallStatus.FAILED -> AcpToolCallStatus.FAILED
    }
}

private fun String.toAcpToolKind(): ToolKind? {
    return when (this.lowercase()) {
        "read" -> ToolKind.READ
        "edit" -> ToolKind.EDIT
        "delete" -> ToolKind.DELETE
        "move" -> ToolKind.MOVE
        "search" -> ToolKind.SEARCH
        "execute", "terminal", "bash" -> ToolKind.EXECUTE
        "think" -> ToolKind.THINK
        "fetch" -> ToolKind.FETCH
        "switch_mode" -> ToolKind.SWITCH_MODE
        "other" -> ToolKind.OTHER
        else -> null
    }
}
