package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.FolderTagDetails

class FolderActionItem(
    private val project: Project,
    private val folder: VirtualFile
) : AbstractLookupActionItem() {

    override val displayName = folder.name
    override val icon = AllIcons.Nodes.Folder

    override fun setPresentation(element: LookupElement, presentation: LookupElementPresentation) {
        super.setPresentation(element, presentation)

        val projectDir = project.guessProjectDir()
        presentation.typeText = if (projectDir != null) {
            VfsUtil.getRelativePath(folder, projectDir) ?: folder.path
        } else {
            folder.path
        }
        presentation.isTypeGrayed = true
    }

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        userInputPanel.addTag(FolderTagDetails(folder))
    }
}