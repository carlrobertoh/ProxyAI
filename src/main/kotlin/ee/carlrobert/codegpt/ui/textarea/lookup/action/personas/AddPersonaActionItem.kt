package ee.carlrobert.codegpt.ui.textarea.lookup.action.personas

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.prompts.PromptsConfigurable
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem

class AddPersonaActionItem : AbstractLookupActionItem() {

    override val displayName: String =
        CodeGPTBundle.get("suggestionActionItem.createPersona.displayName")
    override val icon = AllIcons.General.Add

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        service<ShowSettingsUtil>().showSettingsDialog(
            project,
            PromptsConfigurable::class.java
        )
    }
}