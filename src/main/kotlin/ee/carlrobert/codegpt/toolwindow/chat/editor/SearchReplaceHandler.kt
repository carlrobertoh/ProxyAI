package ee.carlrobert.codegpt.toolwindow.chat.editor

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.vfs.readText
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel.Companion.RESPONSE_EDITOR_DIFF_VIEWER_VALUE_PAIR_KEY
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DiffHeaderPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.state.EditorStateManager
import ee.carlrobert.codegpt.toolwindow.chat.parser.Code
import ee.carlrobert.codegpt.toolwindow.chat.parser.ReplaceWaiting
import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchReplace
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment

class SearchReplaceHandler(
    private val stateManager: EditorStateManager,
    private val onEditorReplaced: (EditorEx, EditorEx) -> Unit
) {
    private var searchFailed = false

    fun handleSearchReplace(item: SearchReplace, partialResponse: Boolean) {
        val editor = stateManager.getCurrentState()?.editor ?: return
        (editor.permanentHeaderComponent as? DiffHeaderPanel)?.handleDone()

        RESPONSE_EDITOR_DIFF_VIEWER_VALUE_PAIR_KEY.set(editor, Pair(item.search, item.replace))
        handleReplace(item, item.filePath, item.search, item.replace)
    }

    fun handleReplace(item: ReplaceWaiting) {
        val editor = stateManager.getCurrentState()?.editor ?: return
        (editor.permanentHeaderComponent as? DiffHeaderPanel)?.editing()

        handleReplace(item, item.filePath, item.search, item.replace)
    }

    private fun handleReplace(
        item: Segment,
        filePath: String?,
        searchContent: String,
        replaceContent: String
    ) {
        val editor = stateManager.getCurrentState()?.editor ?: return

        if (filePath == null && editor.editorKind != EditorKind.DIFF) return

        val virtualFile = CodeGPTKeys.TOOLWINDOW_EDITOR_FILE_DETAILS.get(editor)?.virtualFile
        if (virtualFile == null) {
            if (searchFailed && editor.editorKind == EditorKind.UNTYPED && replaceContent.isNotEmpty()) {
                stateManager.getCurrentState()?.updateContent(item)
            } else {
                handleNonExistentFile(replaceContent)
            }
            return
        }

        val currentText = virtualFile.readText()
        val containsText = currentText.contains(searchContent.trim())

        if (searchContent.isNotEmpty() && editor.editorKind == EditorKind.DIFF && !containsText && !searchFailed) {
            searchFailed = true
            handleFailedDiffSearch(searchContent, replaceContent)
            return
        }

        stateManager.getCurrentState()?.updateContent(item)
    }

    private fun handleNonExistentFile(replaceContent: String) {
        val state = stateManager.getCurrentState() ?: return
        val oldEditor = state.editor
        val segment = Code(replaceContent, state.segment.language, state.segment.filePath)

        val newState = stateManager.createFromSegment(segment)
        val newEditor = newState.editor

        onEditorReplaced(oldEditor, newEditor)
        searchFailed = true
    }

    private fun handleFailedDiffSearch(searchContent: String, replaceContent: String) {
        val oldEditor = stateManager.getCurrentState()?.editor ?: return
        val newState = stateManager.transitionToFailedDiffState(searchContent, replaceContent)
        if (newState != null) {
            val newEditor = newState.editor
            runInEdt {
                onEditorReplaced(oldEditor, newEditor)
            }
        }
    }
}