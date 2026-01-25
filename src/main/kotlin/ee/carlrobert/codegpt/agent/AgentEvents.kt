package ee.carlrobert.codegpt.agent

import ai.koog.prompt.executor.clients.LLMClientException
import ee.carlrobert.codegpt.agent.tools.AskUserQuestionTool
import ee.carlrobert.codegpt.conversations.message.TokenUsage
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.AgentCreditsEvent
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalRequest

interface AgentEvents {
    fun onTextReceived(text: String) {}
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

    fun onRetry(attempt: Int, maxAttempts: Int, reason: String? = null) {}
    fun onQueuedMessagesResolved()
    fun onTokenUsageAvailable(tokenUsage: Long) {}
    fun onTokenUsageUpdated(tokenUsage: TokenUsage) {}
    fun onCreditsAvailable(event: AgentCreditsEvent) {}
    fun onClientException(provider: ServiceType, ex: LLMClientException) {}
    fun onHistoryCompressionStateChanged(isCompressing: Boolean) {}

    suspend fun approveToolCall(request: ToolApprovalRequest): Boolean = false

    suspend fun askUserQuestions(model: AskUserQuestionTool.AskUserQuestionsModel): Map<String, String> =
        emptyMap()
}
