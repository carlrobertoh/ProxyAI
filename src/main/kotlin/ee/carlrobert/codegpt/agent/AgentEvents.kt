package ee.carlrobert.codegpt.agent

import ai.koog.agents.core.feature.handler.agent.AgentCompletedContext
import ai.koog.prompt.executor.clients.LLMClientException
import ee.carlrobert.codegpt.agent.tools.AskUserQuestionTool
import ee.carlrobert.codegpt.conversations.message.TokenUsage
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.AgentCreditsEvent
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalRequest

interface AgentEvents {
    fun onTextReceived(text: String) {}
    fun onAgentCompleted(ctx: AgentCompletedContext) {}
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

    fun onQueuedMessagesResolved()
    fun onTokenUsageAvailable(tokenUsage: Long) {}
    fun onTokenUsageUpdated(tokenUsage: TokenUsage) {}
    fun onCreditsAvailable(event: AgentCreditsEvent) {}
    fun onClientException(provider: ServiceType, ex: LLMClientException) {}

    suspend fun approveToolCall(request: ToolApprovalRequest): Boolean = false

    suspend fun askUserQuestions(model: AskUserQuestionTool.AskUserQuestionsModel): Map<String, String> =
        emptyMap()
}
