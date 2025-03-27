package ee.carlrobert.codegpt.ui.textarea.lookup.action.docs

import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.documentation.DocumentationsConfigurable
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem

class ViewAllDocsActionItem : AbstractLookupActionItem() {

    override val displayName: String =
        "${CodeGPTBundle.get("suggestionActionItem.viewDocumentations.displayName")} →"
    override val icon = null
    override val enabled = GeneralSettings.getSelectedService() == ServiceType.CODEGPT

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        service<ShowSettingsUtil>().showSettingsDialog(
            project,
            DocumentationsConfigurable::class.java
        )
    }
}