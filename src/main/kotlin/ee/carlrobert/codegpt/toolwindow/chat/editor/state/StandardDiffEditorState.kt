package ee.carlrobert.codegpt.toolwindow.chat.editor.state

import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.util.Side
import com.intellij.ide.actions.OpenFileAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.application
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.diff.DiffEditorManager
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DiffHeaderPanel
import ee.carlrobert.codegpt.toolwindow.chat.parser.Code
import ee.carlrobert.codegpt.toolwindow.chat.parser.ReplaceWaiting
import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchReplace
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment
import okhttp3.sse.EventSource

class StandardDiffEditorState(
    editor: EditorEx,
    segment: Segment,
    project: Project,
    diffViewer: UnifiedDiffViewer?,
    virtualFile: VirtualFile?,
    private val diffEditorManager: DiffEditorManager,
    eventSource: EventSource? = null,
    private val originalSuggestion: String? = null
) : DiffEditorState(editor, segment, project, diffViewer, virtualFile, eventSource) {

    override fun applyAllChanges() {
        val before = diffViewer?.getDocument(Side.LEFT)?.text ?: return
        val after = diffViewer.getDocument(Side.RIGHT).text
        val changes = diffEditorManager.applyAllChanges()
        if (changes.isNotEmpty()) {
            (editor.permanentHeaderComponent as? DiffHeaderPanel)
                ?.handleChangesApplied(before, after, changes)
            virtualFile?.let { OpenFileAction.openFile(it, project) }
        }
    }

    override fun updateContent(segment: Segment) {
        if (editor.editorKind == EditorKind.DIFF) {
            val (search, replace) = if (segment is SearchReplace) {
                segment.search to segment.replace
            } else if (segment is ReplaceWaiting) {
                segment.search to segment.replace
            } else {
                return
            }

            diffEditorManager.updateDiffContent(search, replace)
            (editor.permanentHeaderComponent as? DiffHeaderPanel)
                ?.updateDiffStats(diffViewer?.diffChanges ?: emptyList())
        }
    }

    fun refresh() {
        application.executeOnPooledThread {
            runInEdt {
                diffViewer?.rediff(true)
            }
        }
    }

    override fun handleClose() {
        runInEdt {
            val responsePanel = editor.component.parent as? ResponseEditorPanel ?: return@runInEdt
            val contentToKeep = originalSuggestion ?: when (segment) {
                is SearchReplace -> segment.replace
                is ReplaceWaiting -> segment.replace
                else -> diffViewer?.getDocument(Side.RIGHT)?.text ?: ""
            }
            responsePanel.replaceEditorWithSegment(
                Code(contentToKeep, segment.language, segment.filePath)
            )
        }
    }
}
