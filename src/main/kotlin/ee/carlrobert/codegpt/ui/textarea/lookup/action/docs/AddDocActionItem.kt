package ee.carlrobert.codegpt.ui.textarea.lookup.action.docs

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.documentation.DocumentationSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.ui.AddDocumentationDialog
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.DocumentationTagDetails
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem

class AddDocActionItem : AbstractLookupActionItem() {

    override val displayName: String =
        CodeGPTBundle.get("suggestionActionItem.createDocumentation.displayName")
    override val icon = AllIcons.General.Add
    override val enabled = GeneralSettings.getSelectedService() == ServiceType.CODEGPT

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        val addDocumentationDialog = AddDocumentationDialog(project)
        if (addDocumentationDialog.showAndGet()) {
            service<DocumentationSettings>()
                .updateLastUsedDateTime(addDocumentationDialog.documentationDetails.url)
            userInputPanel.addTag(DocumentationTagDetails(addDocumentationDialog.documentationDetails))
        }
    }
}