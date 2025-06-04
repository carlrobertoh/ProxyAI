package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.icons.AllIcons
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.ui.textarea.header.tag.CodeAnalyzeTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.CodeAnalyzeActionItem

class CodeAnalyzeGroupItem(private val tagManager: TagManager) :
    AbstractLookupGroupItem() {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.codeAnalyze.displayName")
    override val icon = AllIcons.Actions.DependencyAnalyzer
    override val enabled: Boolean
        get() = tagManager.getTags().none { it is CodeAnalyzeTagDetails }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        return listOf(CodeAnalyzeActionItem(tagManager))
    }
}