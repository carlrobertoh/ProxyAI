package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.documentation.DocumentationSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.ui.DocumentationDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.DocumentationTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.docs.AddDocActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.docs.DocActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.docs.ViewAllDocsActionItem
import java.time.Instant
import java.time.format.DateTimeParseException

class DocsGroupItem(
    private val tagManager: TagManager
) : AbstractLookupGroupItem() {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.docs.displayName")
    override val icon = AllIcons.Toolwindows.Documentation
    override val enabled: Boolean
        get() = enabled()

    fun enabled(): Boolean {
        if (GeneralSettings.getSelectedService() != ServiceType.CODEGPT) {
            return false
        }

        return tagManager.getTags().none { it is DocumentationTagDetails }
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> =
        listOf(AddDocActionItem(), ViewAllDocsActionItem()) +
                service<DocumentationSettings>().state.documentations
                    .sortedByDescending { parseDateTime(it.lastUsedDateTime) }
                    .filter {
                        searchText.isEmpty() || (it.name?.contains(searchText, true) ?: false)
                    }
                    .take(10)
                    .map {
                        DocActionItem(DocumentationDetails(it.name ?: "", it.url ?: ""))
                    }

    private fun parseDateTime(dateTimeString: String?): Instant {
        return dateTimeString?.let {
            try {
                Instant.parse(it)
            } catch (e: DateTimeParseException) {
                Instant.EPOCH
            }
        } ?: Instant.EPOCH
    }
}