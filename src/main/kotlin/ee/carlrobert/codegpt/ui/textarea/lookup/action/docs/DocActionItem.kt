package ee.carlrobert.codegpt.ui.textarea.lookup.action.docs

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.settings.documentation.DocumentationSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.ui.DocumentationDetails
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.DocumentationTagDetails
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem

class DocActionItem(
    private val documentationDetails: DocumentationDetails
) : AbstractLookupActionItem() {

    override val displayName = documentationDetails.name
    override val icon = AllIcons.Toolwindows.Documentation
    override val enabled = ModelSelectionService.getInstance().getServiceForFeature(FeatureType.CHAT) == ServiceType.PROXYAI

    override fun setPresentation(element: LookupElement, presentation: LookupElementPresentation) {
        super.setPresentation(element, presentation)

        presentation.typeText = documentationDetails.url
        presentation.isTypeGrayed = true
    }

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        service<DocumentationSettings>().updateLastUsedDateTime(documentationDetails.url)
        userInputPanel.addTag(DocumentationTagDetails(documentationDetails))
    }
}