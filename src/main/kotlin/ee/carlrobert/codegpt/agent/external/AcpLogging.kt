package ee.carlrobert.codegpt.agent.external

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.BashPayload
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.EditPayload
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalPayload
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.WritePayload
import kotlinx.serialization.json.JsonElement

internal val ToolCallStatus.wireValue: String
    get() = when (this) {
        ToolCallStatus.PENDING -> "pending"
        ToolCallStatus.IN_PROGRESS -> "in_progress"
        ToolCallStatus.COMPLETED -> "completed"
        ToolCallStatus.FAILED -> "failed"
    }

internal fun String.logPreview(limit: Int = 120): String {
    return replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(limit)
}

internal fun ToolApprovalPayload?.logSummary(): String {
    return when (this) {
        is WritePayload -> "write:${filePath.logPreview(80)}"
        is EditPayload -> "edit:${filePath.logPreview(80)}"
        is BashPayload -> "bash:${command.logPreview(80)}"
        null -> "none"
    }
}

internal fun JsonElement?.logSummary(limit: Int = 400): String {
    return this?.toString()?.replace(Regex("\\s+"), " ")?.take(limit) ?: "null"
}

internal fun Any?.logSummary(limit: Int = 400): String {
    return this?.toString()?.replace(Regex("\\s+"), " ")?.take(limit) ?: "null"
}

internal fun SessionUpdate.logSummary(): String {
    return when (this) {
        is SessionUpdate.ToolCall -> "toolCallId=${toolCallId.value} title=${title.logPreview()} kind=${kind?.name} status=${status?.wireValue} rawInput=${rawInput.logSummary()}"
        is SessionUpdate.ToolCallUpdate -> "toolCallId=${toolCallId.value} title=${title?.logPreview()} kind=${kind?.name} status=${status?.wireValue} rawInput=${rawInput.logSummary()} rawOutput=${rawOutput.logSummary()}"
        is SessionUpdate.AgentMessageChunk -> "message=${(content as? ContentBlock.Text)?.text?.logPreview()}"
        is SessionUpdate.AgentThoughtChunk -> "thought=${(content as? ContentBlock.Text)?.text?.logPreview()}"
        is SessionUpdate.CurrentModeUpdate -> "currentMode=${currentModeId.value}"
        is SessionUpdate.PlanUpdate -> "planEntries=${entries.size}"
        is SessionUpdate.ConfigOptionUpdate -> "configOptions=${configOptions.size}"
        is SessionUpdate.SessionInfoUpdate -> "title=${title?.logPreview().orEmpty()} updatedAt=${updatedAt.orEmpty()}"
        is SessionUpdate.UsageUpdate -> "used=$used size=$size cost=${cost.logSummary()}"
        is SessionUpdate.UnknownSessionUpdate -> "type=$sessionUpdateType raw=${rawJson.logSummary()}"
        is SessionUpdate.AvailableCommandsUpdate -> "availableCommands=${availableCommands.size}"
        else -> this::class.simpleName.orEmpty()
    }
}
