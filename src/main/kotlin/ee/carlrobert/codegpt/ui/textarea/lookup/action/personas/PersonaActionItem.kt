package ee.carlrobert.codegpt.ui.textarea.lookup.action.personas

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.settings.prompts.PersonaDetails
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.PersonaTagDetails
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem

class PersonaActionItem(
    private val personaDetails: PersonaDetails
) : AbstractLookupActionItem() {

    override val displayName = personaDetails.name
    override val icon = AllIcons.General.User

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        userInputPanel.addTag(PersonaTagDetails(personaDetails))
    }
}
