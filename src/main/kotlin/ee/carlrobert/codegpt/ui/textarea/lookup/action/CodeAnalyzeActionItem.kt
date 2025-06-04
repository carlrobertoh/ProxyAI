package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.CodeAnalyzeTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager

class CodeAnalyzeActionItem(
    private val tagManager: TagManager
) : AbstractLookupActionItem() {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.codeAnalyze.displayName")
    override val icon = AllIcons.Actions.DependencyAnalyzer
    override val enabled: Boolean
        get() = tagManager.getTags().none { it is CodeAnalyzeTagDetails } &&
                tagManager.getTags().any { it is FileTagDetails || it is EditorTagDetails }


    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        userInputPanel.addTag(CodeAnalyzeTagDetails())
    }
}