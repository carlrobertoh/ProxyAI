package ee.carlrobert.codegpt.ui.textarea.lookup.action.git

import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.CurrentGitChangesTagDetails
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem
import javax.swing.Icon

class IncludeCurrentChangesActionItem : AbstractLookupActionItem() {

    override val displayName: String =
        CodeGPTBundle.get("suggestionActionItem.includeCurrentChanges.displayName")
    override val icon: Icon? = null

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        userInputPanel.addTag(CurrentGitChangesTagDetails())
    }
}