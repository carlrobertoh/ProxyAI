package ee.carlrobert.codegpt.toolwindow.chat.editor.factory

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.ContextMenuPopupHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.intellij.ui.ColorUtil
import com.intellij.util.application
import com.intellij.util.ui.JBUI
import com.intellij.vcsUtil.VcsUtil.getVirtualFile
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.predictions.CodeSuggestionDiffViewer.MyDiffContext
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.ToolWindowEditorFileDetails
import ee.carlrobert.codegpt.toolwindow.chat.editor.diff.DiffSyncManager
import ee.carlrobert.codegpt.toolwindow.chat.parser.ReplaceWaiting
import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchReplace
import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchWaiting
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.file.FileUtil
import javax.swing.JComponent

object EditorFactory {

    private val logger = thisLogger()

    fun createEditor(project: Project, segment: Segment): EditorEx {
        val content = segment.content
        val languageMapping = FileUtil.findLanguageExtensionMapping(segment.language)
        val isDiffType = isDiffType(segment, content)
        return invokeAndWaitIfNeeded {
            val editor = if (isDiffType) {
                createDiffEditor(project, segment)
                    ?: EditorUtil.createEditor(project, languageMapping.value, content)
            } else {
                EditorUtil.createEditor(project, languageMapping.value, content)
            } as EditorEx
            segment.filePath?.let { filePath ->
                application.executeOnPooledThread {
                    CodeGPTKeys.TOOLWINDOW_EDITOR_FILE_DETAILS.set(
                        editor,
                        ToolWindowEditorFileDetails(filePath, getVirtualFile(filePath))
                    )
                }
                DiffSyncManager.registerEditor(filePath, editor)
            }
            editor
        }
    }

    fun configureEditor(editor: EditorEx, headerComponent: JComponent? = null) {
        editor.permanentHeaderComponent = headerComponent
        editor.headerComponent = null

        val diffKind = editor.editorKind == EditorKind.DIFF
        editor.settings.apply {
            additionalColumnsCount = 0
            additionalLinesCount = 0
            isAdditionalPageAtBottom = false
            isVirtualSpace = false
            isUseSoftWraps = false
            isLineNumbersShown = diffKind
            isLineMarkerAreaShown = diffKind
        }
        editor.gutterComponentEx.apply {
            isVisible = diffKind
            parent.isVisible = diffKind
        }

        editor.contentComponent.border = JBUI.Borders.emptyLeft(4)
        editor.setBorder(JBUI.Borders.customLine(ColorUtil.fromHex("#48494b")))
        editor.installPopupHandler(
            ContextMenuPopupHandler.Simple(
                ComponentFactory.createEditorActionGroup(editor)
            )
        )
    }

    private fun createDiffEditor(project: Project, segment: Segment): EditorEx? {
        val filePath = segment.filePath
        if (filePath == null) {
            logger.warn("Cannot create diff editor for non-existent path")
            return null
        }

        val virtualFile = ApplicationManager.getApplication().executeOnPooledThread<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
        }.get() ?: return null
        val leftContent = DiffContentFactory.getInstance().create(project, virtualFile)

        val rightContentDoc = EditorFactory.getInstance()
            .createDocument(StringUtil.convertLineSeparators(EditorUtil.getFileContent(virtualFile)))
        rightContentDoc.setReadOnly(false)

        val rightContent =
            DiffContentFactory.getInstance().create(project, rightContentDoc, virtualFile)
        val diffRequest = SimpleDiffRequest(
            "Code Diff",
            listOf(leftContent, rightContent),
            listOf("Original", "Modified")
        )

        val diffViewer = UnifiedDiffViewer(MyDiffContext(project), diffRequest)
        ResponseEditorPanel.RESPONSE_EDITOR_DIFF_VIEWER_KEY.set(diffViewer.editor, diffViewer)
        return diffViewer.editor
    }

    private fun isDiffType(segment: Segment, content: String): Boolean {
        return segment is ReplaceWaiting
                || segment is SearchWaiting
                || segment is SearchReplace
                || content.startsWith("<<<")
    }
}
