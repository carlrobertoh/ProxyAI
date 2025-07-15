package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.WebTagDetails

class WebActionItem(private val tagManager: TagManager) : AbstractLookupActionItem() {

    override val displayName: String =
        CodeGPTBundle.get("suggestionActionItem.webSearch.displayName")
    override val icon = AllIcons.General.Web
    override val enabled: Boolean
        get() = enabled()

    fun enabled(): Boolean {
        if (ModelSelectionService.getInstance().getServiceForFeature(FeatureType.CHAT) != ServiceType.PROXYAI) {
            return false
        }
        return tagManager.getTags().none { it is WebTagDetails }
    }

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        userInputPanel.addTag(WebTagDetails())
    }
}