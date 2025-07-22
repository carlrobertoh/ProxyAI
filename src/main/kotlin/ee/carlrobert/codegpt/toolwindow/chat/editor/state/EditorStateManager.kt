package ee.carlrobert.codegpt.toolwindow.chat.editor.state

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel.Companion.RESPONSE_EDITOR_STATE_KEY
import ee.carlrobert.codegpt.toolwindow.chat.editor.diff.DiffEditorManager
import ee.carlrobert.codegpt.toolwindow.chat.editor.factory.EditorFactory
import ee.carlrobert.codegpt.toolwindow.chat.parser.Code
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment

class EditorStateManager(private val project: Project) {

    private var currentState: EditorState? = null
    private var diffEditorManager: DiffEditorManager? = null

    fun createFromSegment(segment: Segment, readOnly: Boolean = false): EditorState {
        val editor = EditorFactory.createEditor(project, segment)
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

    fun transitionToFailedDiffState(searchContent: String, replaceContent: String): EditorState? {
        val currentState = this.currentState ?: return null

        val segment = currentState.segment
        val virtualFile = getVirtualFile(segment.filePath) ?: return null

        return transitionToFailedDiffState(searchContent, replaceContent, virtualFile)
    }

    fun transitionToFailedDiffState(
        searchContent: String,
        replaceContent: String,
        virtualFile: VirtualFile
    ): EditorState? {
        val newSegment = Code(replaceContent, virtualFile.extension ?: "Text", virtualFile.path)
        val newEditor = EditorFactory.createEditor(project, newSegment)

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
