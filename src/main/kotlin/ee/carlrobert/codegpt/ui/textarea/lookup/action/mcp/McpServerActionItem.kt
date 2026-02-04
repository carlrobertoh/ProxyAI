package ee.carlrobert.codegpt.ui.textarea.lookup.action.mcp

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.mcp.McpStatusBridge
import ee.carlrobert.codegpt.settings.mcp.McpServerDetailsState
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem

class McpServerActionItem(
    private val serverDetails: McpServerDetailsState
) : AbstractLookupActionItem() {

    override val displayName = serverDetails.name ?: "Unknown Server"
    override val icon = Icons.MCP

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        val conversationId = userInputPanel.getConversationId()
            ?: ConversationsState.getCurrentConversation()?.id
        if (conversationId == null) {
            return
        }

        val statusBridge = service<McpStatusBridge>()
        statusBridge.attachServerAndUpdateUi(
            conversationId,
            serverDetails.id.toString(),
            serverDetails.name ?: "Unknown Server",
            userInputPanel
        )
    }
}
