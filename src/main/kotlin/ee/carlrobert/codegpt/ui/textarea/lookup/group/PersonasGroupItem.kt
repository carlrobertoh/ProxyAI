package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.prompts.PersonaDetails
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.ui.textarea.header.tag.PersonaTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.personas.AddPersonaActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.personas.PersonaActionItem

class PersonasGroupItem(private val tagManager: TagManager) :
    AbstractLookupGroupItem() {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.personas.displayName")
    override val icon = AllIcons.General.User
    override val enabled: Boolean
        get() = tagManager.getTags().none { it is PersonaTagDetails }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        return listOf(AddPersonaActionItem()) + service<PromptsSettings>().state.personas.prompts
            .map {
                PersonaDetails(it.id, it.name ?: "Unknown", it.instructions ?: "Unknown")
            }
            .filter {
                searchText.isEmpty() || it.name.contains(searchText, true)
            }
            .map { PersonaActionItem(it) }
            .take(10)
    }
}