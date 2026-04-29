package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.icons.AllIcons
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.HistoryActionItem

class HistoryGroupItem : AbstractLookupGroupItem(), DynamicLookupGroupItem {

    override val displayName: String =
        CodeGPTBundle.get("suggestionGroupItem.history.displayName")
    override val icon = AllIcons.Vcs.History

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        return ConversationsState.getInstance().conversations
            .sortedByDescending { it.updatedOn }
            .filter { conversation ->
                if (searchText.isEmpty()) {
                    true
                } else {
                    val title = HistoryActionItem.getConversationTitle(conversation)
                    title.contains(searchText, ignoreCase = true)
                }
            }
            .map { HistoryActionItem(it) }
    }
}
