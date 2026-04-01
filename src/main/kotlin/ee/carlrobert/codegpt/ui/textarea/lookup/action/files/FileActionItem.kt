package ee.carlrobert.codegpt.ui.textarea.lookup.action.files

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.FileSearchSource
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.InsertsDisplayNameLookupItem

class FileActionItem(
    private val project: Project,
    val file: VirtualFile,
    val source: FileSearchSource = FileSearchSource.OPEN
) :
    AbstractLookupActionItem(), InsertsDisplayNameLookupItem {

    override val displayName = file.name
    override val icon = file.fileType.icon ?: AllIcons.FileTypes.Any_type

    override fun setPresentation(element: LookupElement, presentation: LookupElementPresentation) {
        super.setPresentation(element, presentation)

        val projectDir = project.guessProjectDir()
        presentation.typeText = if (projectDir != null) {
            VfsUtil.getRelativePath(file, projectDir) ?: file.path
        } else {
            file.path
        }
        presentation.isTypeGrayed = true
    }

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        userInputPanel.addTag(FileTagDetails(file))
    }
}
