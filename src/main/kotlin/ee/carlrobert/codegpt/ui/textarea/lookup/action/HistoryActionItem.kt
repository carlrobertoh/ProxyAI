package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.HistoryTagDetails
import javax.swing.Icon

class HistoryActionItem(
    private val conversation: Conversation,
) : AbstractLookupActionItem() {

    companion object {
        fun getConversationTitle(conversation: Conversation): String {
            return conversation.messages.firstOrNull()?.let { firstMessage ->
                firstMessage.prompt?.take(60) ?: firstMessage.response?.take(60)
            } ?: "Conversation"
        }
    }

    override val displayName: String
        get() = getConversationTitle(conversation)

    override val icon: Icon
        get() = AllIcons.General.Balloon

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        userInputPanel.addTag(
            HistoryTagDetails(
                conversationId = conversation.id,
                title = displayName,
            )
        )
    }
}