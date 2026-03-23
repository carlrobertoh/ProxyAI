package ee.carlrobert.codegpt.agent.external

import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.AvailableCommand
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.ToolKind
import ee.carlrobert.codegpt.agent.AgentEvents
import ee.carlrobert.codegpt.agent.AgentUsageEvent
import ee.carlrobert.codegpt.agent.external.events.AcpExternalEvent
import ee.carlrobert.codegpt.agent.external.events.AcpToolCallArgs
import ee.carlrobert.codegpt.agent.external.events.AcpToolCallSnapshot
import java.util.concurrent.ConcurrentHashMap

internal class AcpSessionUpdateBridge(
    private val proxySessionId: String,
    private val toolEventFlavor: AcpToolEventFlavor,
    private val toolCallDecoder: AcpToolCallDecoder,
    private val updateModeSelection: (String) -> Unit,
    private val updateConfigOptions: (List<SessionConfigOption>) -> Unit,
    private val updateAvailableCommands: (List<AvailableCommand>) -> Unit,
    private val updateSessionInfo: (String?, String?) -> Unit,
    private val trace: (String) -> Unit
) {
    private val toolCallsById = ConcurrentHashMap<String, AcpToolCallSnapshot>()

    fun handle(notification: SessionNotification, events: AgentEvents) {
        trace(
            "session/update session=$proxySessionId update=${notification.update::class.simpleName} payload=${notification.update.logSummary()}"
        )
        when (val event =
            toolCallDecoder.decodeExternalEvent(toolEventFlavor, notification.update)) {
            is AcpExternalEvent.TextChunk -> events.onTextReceived(event.text)
            is AcpExternalEvent.ThinkingChunk -> events.onThinkingReceived(event.text)
            is AcpExternalEvent.PlanUpdate -> events.onPlanUpdated(event.entries)
            is AcpExternalEvent.UsageUpdate -> events.onUsageAvailable(
                AgentUsageEvent(
                    usedTokens = event.used,
                    sizeTokens = event.size.takeIf { it > 0L },
                    costAmount = event.cost?.amount,
                    costCurrency = event.cost?.currency
                )
            )
            is AcpExternalEvent.ConfigOptionUpdate -> {
                updateConfigOptions(event.configOptions)
                events.onRuntimeOptionsUpdated()
            }
            is AcpExternalEvent.SessionInfoUpdate -> {
                updateSessionInfo(event.title, event.updatedAt)
                events.onSessionInfoUpdated(event.title, event.updatedAt)
            }
            is AcpExternalEvent.UnknownSessionUpdate -> trace(
                "session/update/unknown session=$proxySessionId type=${event.type} payload=${event.rawJson.logSummary()}"
            )
            is AcpExternalEvent.AvailableCommandsUpdate -> updateAvailableCommands(event.availableCommands)
            is AcpExternalEvent.CurrentModeUpdate -> {
                updateModeSelection(event.currentModeId)
                events.onRuntimeOptionsUpdated()
            }
            is AcpExternalEvent.ToolCallStarted -> handleToolCallStarted(event.toolCall, events)
            is AcpExternalEvent.ToolCallUpdated -> handleToolCallUpdated(event.toolCall, events)
            else -> Unit
        }
    }

    private fun handleToolCallStarted(
        toolCall: AcpToolCallSnapshot,
        events: AgentEvents
    ) {
        trace(
            "tool-call/start session=$proxySessionId id=${toolCall.id} title=${toolCall.title.logPreview()} kind=${toolCall.kind?.name} status=${toolCall.status?.wireValue} rawInput=${toolCall.rawInput.logSummary()}"
        )
        if (toolCall.kind == ToolKind.THINK && toolCall.title.isNotBlank()) {
            events.onThinkingReceived(toolCall.title)
            return
        }
        toolCallsById[toolCall.id] = toolCall
        if (!shouldDeferToolStart(toolCall)) {
            events.onToolStarting(toolCall.id, toolCall.toolName, toolCall.args.toUiArgs())
        }

        if (toolCall.status?.isTerminal == true) {
            completeToolCall(toolCall, events)
        }
    }

    private fun handleToolCallUpdated(
        toolCall: AcpToolCallSnapshot,
        events: AgentEvents
    ) {
        trace(
            "tool-call/update session=$proxySessionId id=${toolCall.id} title=${toolCall.title.logPreview()} kind=${toolCall.kind?.name} status=${toolCall.status?.wireValue} rawInput=${toolCall.rawInput.logSummary()} rawOutput=${toolCall.rawOutput.logSummary()}"
        )
        val currentToolCall = toolCallsById[toolCall.id]
        val effectiveToolCall = mergeToolCallSnapshots(currentToolCall, toolCall)
        val status = effectiveToolCall.status ?: return

        toolCallsById[effectiveToolCall.id] = effectiveToolCall

        if ((currentToolCall?.args == null && effectiveToolCall.args != null) ||
            (currentToolCall == null && !shouldDeferToolStart(effectiveToolCall))
        ) {
            events.onToolStarting(
                effectiveToolCall.id,
                effectiveToolCall.toolName,
                effectiveToolCall.args.toUiArgs()
            )
        }

        if (!status.isTerminal) {
            return
        }

        completeToolCall(effectiveToolCall, events)
    }

    private fun completeToolCall(
        toolCall: AcpToolCallSnapshot,
        events: AgentEvents
    ) {
        val result = toolCallDecoder.decodeResult(toolCall, toolCall.rawOutput)
        trace(
            "tool-call/complete session=$proxySessionId id=${toolCall.id} tool=${toolCall.toolName} status=${toolCall.status?.wireValue} result=${result.logSummary()}"
        )
        toolCallsById.remove(toolCall.id)
        events.onToolCompleted(toolCall.id, toolCall.toolName, result)
    }

    private fun shouldDeferToolStart(toolCall: AcpToolCallSnapshot): Boolean {
        return toolCall.toolName in setOf("WebSearch", "WebFetch", "Write", "Edit") &&
            toolCall.args == null &&
            toolCall.status?.isTerminal != true
    }

    private fun AcpToolCallArgs?.toUiArgs(): Any? {
        return when (this) {
            is AcpToolCallArgs.SearchPreview -> value
            is AcpToolCallArgs.BashPreview -> value
            is AcpToolCallArgs.Mcp -> value
            is AcpToolCallArgs.Read -> value
            is AcpToolCallArgs.Write -> value
            is AcpToolCallArgs.Edit -> value
            is AcpToolCallArgs.Bash -> value
            is AcpToolCallArgs.WebSearch -> value
            is AcpToolCallArgs.WebFetch -> value
            is AcpToolCallArgs.IntelliJSearch -> value
            is AcpToolCallArgs.Unknown -> value
            null -> null
        }
    }

    private fun mergeToolCallSnapshots(
        currentToolCall: AcpToolCallSnapshot?,
        updatedToolCall: AcpToolCallSnapshot
    ): AcpToolCallSnapshot {
        val effectiveTitle = updatedToolCall.title.ifBlank { currentToolCall?.title.orEmpty() }
            .ifBlank { "Tool" }
        val effectiveToolName = when {
            updatedToolCall.toolName.isNotBlank() && updatedToolCall.toolName != "Tool" -> updatedToolCall.toolName
            currentToolCall != null -> currentToolCall.toolName
            else -> "Tool"
        }
        return updatedToolCall.copy(
            title = effectiveTitle,
            toolName = effectiveToolName,
            kind = updatedToolCall.kind ?: currentToolCall?.kind,
            status = updatedToolCall.status ?: currentToolCall?.status,
            args = updatedToolCall.args ?: currentToolCall?.args,
            locations = updatedToolCall.locations.ifEmpty { currentToolCall?.locations.orEmpty() },
            content = updatedToolCall.content.ifEmpty { currentToolCall?.content.orEmpty() },
            meta = updatedToolCall.meta ?: currentToolCall?.meta,
            rawInput = updatedToolCall.rawInput ?: currentToolCall?.rawInput,
            rawOutput = updatedToolCall.rawOutput ?: currentToolCall?.rawOutput
        )
    }
}
