package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.DiagnosticsTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.util.EditorUtil

class DiagnosticsActionItem(
    private val tagManager: TagManager
) : AbstractLookupActionItem() {

    override val displayName: String = "Diagnostics"
    override val icon = AllIcons.General.InspectionsEye
    override val enabled: Boolean
        get() = tagManager.getTags().none { it is DiagnosticsTagDetails } &&
                tagManager.getTags().any { it is FileTagDetails || it is EditorTagDetails }

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        val virtualFile = findVirtualFile(project)
        virtualFile?.let { file ->
            userInputPanel.addTag(DiagnosticsTagDetails(file))
        }
    }

    private fun findVirtualFile(project: Project): VirtualFile? {
        val existingFile = tagManager.getTags()
            .firstNotNullOfOrNull { tag ->
                when (tag) {
                    is FileTagDetails -> tag.virtualFile
                    is EditorTagDetails -> tag.virtualFile
                    else -> null
                }
            }
        return existingFile ?: EditorUtil.getSelectedEditor(project)?.virtualFile
    }
}