package ee.carlrobert.codegpt.toolwindow.chat.editor.state

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.completions.AutoApplyParameters
import ee.carlrobert.codegpt.completions.CompletionRequestService
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel.Companion.RESPONSE_EDITOR_STATE_KEY
import ee.carlrobert.codegpt.toolwindow.chat.editor.AutoApplyListener
import ee.carlrobert.codegpt.toolwindow.chat.editor.diff.DiffEditorManager
import ee.carlrobert.codegpt.toolwindow.chat.editor.factory.EditorFactory
import ee.carlrobert.codegpt.toolwindow.chat.parser.Code
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment

@Service(Service.Level.PROJECT)
class EditorStateManager(private val project: Project) {

    private var currentState: EditorState? = null
    private var diffEditorManager: DiffEditorManager? = null

    fun createFromSegment(segment: Segment, readOnly: Boolean = false): EditorState {
        val editor = EditorFactory.createEditor(project, segment, readOnly)
        val state = if (editor.editorKind == EditorKind.DIFF) {
            createDiffState(editor, segment)
        } else {
            RegularEditorState(editor, segment, project)
        }
        runInEdt {
            EditorFactory.configureEditor(editor, state.createHeaderComponent(readOnly))
        }

        RESPONSE_EDITOR_STATE_KEY.set(editor, state)
        currentState = state
        return state
    }

    fun getCodeEditsAsync(
        content: String,
        virtualFile: VirtualFile,
        editor: EditorEx,
    ) {
        val params = AutoApplyParameters(content, virtualFile)
        val listener = AutoApplyListener(project, this) { newEditor ->
            val responseEditorPanel = editor.component.parent as? ResponseEditorPanel
                ?: throw IllegalStateException("Expected parent to be ResponseEditorPanel")
            responseEditorPanel.replaceEditor(editor, newEditor)
        }

        CompletionRequestService.getInstance().getCodeEditsAsync(params, listener)
    }

    fun transitionToFailedDiffState(searchContent: String, replaceContent: String): EditorState? {
        val currentState = this.currentState ?: return null

        val segment = currentState.segment
        val virtualFile = getVirtualFile(segment.filePath) ?: return null

        val newSegment = Code(replaceContent, virtualFile.extension ?: "Text", virtualFile.path)
        val newEditor = EditorFactory.createEditor(project, newSegment, false)

        val newState =
            FailedDiffEditorState(newEditor, newSegment, project, searchContent, replaceContent)

        runInEdt {
            EditorFactory.configureEditor(newEditor, newState.createHeaderComponent(false))
        }

        this.currentState = newState

        return newState
    }

    fun getCurrentState(): EditorState? {
        return currentState
    }

    private fun createDiffState(editor: EditorEx, segment: Segment): EditorState {
        val virtualFile = getVirtualFile(segment.filePath)
        val diffViewer = ResponseEditorPanel.RESPONSE_EDITOR_DIFF_VIEWER_KEY.get(editor)
        val diffEditorManager = DiffEditorManager(project, diffViewer, virtualFile)
        this.diffEditorManager = diffEditorManager
        return StandardDiffEditorState(
            editor,
            segment,
            project,
            diffViewer,
            virtualFile,
            diffEditorManager
        )
    }

    private fun getVirtualFile(filePath: String?): VirtualFile? {
        return filePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
    }
}
