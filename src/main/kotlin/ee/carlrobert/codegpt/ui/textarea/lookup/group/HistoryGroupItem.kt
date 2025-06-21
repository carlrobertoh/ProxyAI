package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.runInEdt
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.action.HistoryActionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HistoryGroupItem : AbstractLookupGroupItem(), DynamicLookupGroupItem {

    override val displayName: String =
        CodeGPTBundle.get("suggestionGroupItem.history.displayName")
    override val icon = AllIcons.Vcs.History

    private val addedItems = mutableSetOf<String>()

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

    override suspend fun updateLookupList(lookup: LookupImpl, searchText: String) {
        val filteredItems = getLookupItems(searchText)

        withContext(Dispatchers.Default) {
            if (searchText.isEmpty()) {
                addedItems.clear()
            }

            filteredItems.forEach { item ->
                val itemKey = item.displayName
                if (!addedItems.contains(itemKey)) {
                    addedItems.add(itemKey)
                    runInEdt {
                        if (!lookup.isLookupDisposed) {
                            LookupUtil.addLookupItem(lookup, item)
                        }
                    }
                }
            }
        }
    }
}