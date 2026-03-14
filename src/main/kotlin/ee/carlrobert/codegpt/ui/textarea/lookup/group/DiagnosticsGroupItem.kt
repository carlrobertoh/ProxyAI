package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.icons.AllIcons
import ee.carlrobert.codegpt.diagnostics.DiagnosticsFilter
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.DiagnosticsFilterActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.selectedContextFiles

class DiagnosticsGroupItem(
    private val tagManager: TagManager
) : AbstractLookupGroupItem() {

    override val displayName: String = "Diagnostics"
    override val icon = AllIcons.General.InspectionsEye
    override val enabled: Boolean
        get() = selectedContextFiles(tagManager.getTags()).isNotEmpty()

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        return listOf(
            DiagnosticsFilterActionItem(tagManager, DiagnosticsFilter.ERRORS_ONLY),
            DiagnosticsFilterActionItem(tagManager, DiagnosticsFilter.ALL)
        ).filter {
            searchText.isEmpty() || it.displayName.contains(searchText, ignoreCase = true)
        }
    }
}
