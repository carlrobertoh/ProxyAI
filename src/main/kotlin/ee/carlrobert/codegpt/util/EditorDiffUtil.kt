package ee.carlrobert.codegpt.util

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import ee.carlrobert.codegpt.CodeGPTBundle

object EditorDiffUtil {

    @JvmStatic
    fun showDiff(
        project: Project,
        toolwindowEditor: Editor,
        highlightedText: String
    ) {
        val mainEditor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val mainEditorFile =
            service<FileDocumentManager>().getFile(mainEditor.document) ?: return
        val tempFile = LightVirtualFile(
            mainEditorFile.name,
            createTempDiffContent(mainEditor, toolwindowEditor, highlightedText)
        )
        DiffManager.getInstance()
            .showDiff(project, createDiffRequest(project, tempFile, mainEditor))
    }

    private fun createTempDiffContent(
        mainEditor: Editor,
        toolwindowEditor: Editor,
        previousSelection: String
    ): String {
        val mainDocumentContent = mainEditor.document.text
        val startIndex = mainDocumentContent.indexOf(previousSelection)
        val endIndex = startIndex + previousSelection.length
        return mainDocumentContent.substring(0, startIndex) +
                toolwindowEditor.document.text +
                mainDocumentContent.substring(endIndex)
    }

    @JvmStatic
    fun createDiffRequest(
        project: Project,
        tempFile: VirtualFile,
        mainEditor: Editor,
    ): SimpleDiffRequest {
        val diffContentFactory = DiffContentFactory.getInstance()
        val tempFileDiffContent = diffContentFactory.create(project, tempFile).apply {
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        }

        return SimpleDiffRequest(
            CodeGPTBundle.get("editor.diff.title"),
            diffContentFactory.create(project, (mainEditor as EditorEx).virtualFile),
            tempFileDiffContent,
            mainEditor.virtualFile.name,
            CodeGPTBundle.get("editor.diff.local.content.title")
        ).apply {
            putUserData(
                DiffUserDataKeys.SCROLL_TO_LINE,
                Pair.create(Side.RIGHT, DiffUtil.getCaretPosition(mainEditor).line)
            )
        }
    }
}
