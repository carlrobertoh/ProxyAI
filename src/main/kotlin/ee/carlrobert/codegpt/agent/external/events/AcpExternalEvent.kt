package ee.carlrobert.codegpt.agent.external.events

import com.agentclientprotocol.model.*
import ee.carlrobert.codegpt.agent.external.AcpToolCallStatus
import ee.carlrobert.codegpt.agent.tools.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal sealed interface AcpExternalEvent {
    data class TextChunk(val text: String) : AcpExternalEvent
    data class ThinkingChunk(val text: String) : AcpExternalEvent
    data class PlanUpdate(val entries: List<PlanEntry>) : AcpExternalEvent
    data class UsageUpdate(val used: Long, val size: Long, val cost: Cost?) : AcpExternalEvent
    data class ConfigOptionUpdate(val configOptions: List<SessionConfigOption>) : AcpExternalEvent
    data class SessionInfoUpdate(val title: String?, val updatedAt: String?) : AcpExternalEvent
    data class UnknownSessionUpdate(val type: String, val rawJson: JsonObject) : AcpExternalEvent
    data class AvailableCommandsUpdate(val availableCommands: List<AvailableCommand>) :
        AcpExternalEvent

    data class CurrentModeUpdate(val currentModeId: String) : AcpExternalEvent
    data class ToolCallStarted(val toolCall: AcpToolCallSnapshot) : AcpExternalEvent
    data class ToolCallUpdated(val toolCall: AcpToolCallSnapshot) : AcpExternalEvent
}

internal sealed interface AcpToolCallArgs {
    data class SearchPreview(val value: AcpSearchPreviewArgs) : AcpToolCallArgs
    data class BashPreview(val value: AcpBashPreviewArgs) : AcpToolCallArgs
    data class Mcp(val value: McpTool.Args) : AcpToolCallArgs
    data class Read(val value: ReadTool.Args) : AcpToolCallArgs
    data class Write(val value: WriteTool.Args) : AcpToolCallArgs
    data class Edit(val value: EditTool.Args) : AcpToolCallArgs
    data class Bash(val value: BashTool.Args) : AcpToolCallArgs
    data class WebSearch(val value: WebSearchTool.Args) : AcpToolCallArgs
    data class WebFetch(val value: WebFetchTool.Args) : AcpToolCallArgs
    data class IntelliJSearch(val value: IntelliJSearchTool.Args) : AcpToolCallArgs
    data class Unknown(val value: JsonElement?) : AcpToolCallArgs
}

internal data class AcpSearchPreviewArgs(
    val title: String,
    val path: String? = null,
    val pattern: String? = null
)

internal data class AcpBashPreviewArgs(
    val title: String,
    val command: String? = null
)

internal sealed interface AcpToolCallContent {
    data class Block(val content: ContentBlock) : AcpToolCallContent
    data class Diff(val path: String, val oldText: String?, val newText: String) :
        AcpToolCallContent

    data class Terminal(val terminalId: String) : AcpToolCallContent
}

internal data class AcpToolCallSnapshot(
    val id: String,
    val title: String,
    val toolName: String,
    val kind: ToolKind? = null,
    val status: AcpToolCallStatus? = null,
    val args: AcpToolCallArgs? = null,
    val locations: List<ToolCallLocation> = emptyList(),
    val content: List<AcpToolCallContent> = emptyList(),
    val meta: JsonElement? = null,
    val rawInput: JsonElement? = null,
    val rawOutput: JsonElement? = null
)

internal data class AcpPermissionRequestSnapshot(
    val toolCall: AcpToolCallSnapshot,
    val details: String,
    val options: List<PermissionOption>
)

internal fun ToolCallContent.toAcpToolCallContent(): AcpToolCallContent {
    return when (this) {
        is ToolCallContent.Content -> AcpToolCallContent.Block(content)
        is ToolCallContent.Diff -> AcpToolCallContent.Diff(path, oldText, newText)
        is ToolCallContent.Terminal -> AcpToolCallContent.Terminal(terminalId)
    }
}

internal fun List<ToolCallContent>.toAcpToolCallContent(): List<AcpToolCallContent> {
    return map(ToolCallContent::toAcpToolCallContent)
}
