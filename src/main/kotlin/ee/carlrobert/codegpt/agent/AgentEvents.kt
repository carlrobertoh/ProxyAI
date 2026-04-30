package ee.carlrobert.codegpt.agent

import com.agentclientprotocol.model.PlanEntry
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.agent.tools.AskUserQuestionTool
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.AgentCreditsEvent
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalRequest
import java.util.UUID

interface AgentEvents {
    fun onTextReceived(text: String) {}
    fun onThinkingReceived(text: String) {}
    fun onPlanUpdated(entries: List<PlanEntry>) {}
    fun onAgentCompleted(agentId: String) {}
    fun onToolStarting(id: String, toolName: String, args: Any?) {}
    fun onToolCompleted(id: String?, toolName: String, result: Any?) {}
    fun onSubAgentToolStarting(parentId: String, toolName: String, args: Any?): String? = null
    fun onSubAgentToolCompleted(
        parentId: String,
        childId: String?,
        toolName: String,
        result: Any?
    ) {
    }

    fun onRetry(attempt: Int, maxAttempts: Int) {}
    fun onRetrySucceeded() {}
    fun onRunCheckpointUpdated(runMessageId: UUID, ref: CheckpointRef?) {}
    fun onQueuedMessagesResolved(message: MessageWithContext? = null)
    fun onTokenUsageAvailable(tokenUsage: Long) {}
    fun onUsageAvailable(event: AgentUsageEvent) {
        onTokenUsageAvailable(event.usedTokens)
    }
    fun onRuntimeOptionsUpdated() {}
    fun onSessionInfoUpdated(title: String?, updatedAt: String? = null) {}
    fun onCreditsAvailable(event: AgentCreditsEvent) {}
    fun onAgentException(provider: ServiceType, throwable: Throwable) {}
    fun onHistoryCompressionStateChanged(isCompressing: Boolean) {}

    suspend fun approveToolCall(request: ToolApprovalRequest): Boolean = false

    suspend fun askUserQuestions(model: AskUserQuestionTool.AskUserQuestionsModel): Map<String, String> =
        emptyMap()
}
