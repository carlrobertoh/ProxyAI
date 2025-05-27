package ee.carlrobert.codegpt.toolwindow.chat.editor

import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.diff.DiffSyncManager
import ee.carlrobert.codegpt.toolwindow.chat.editor.factory.ComponentFactory
import ee.carlrobert.codegpt.toolwindow.chat.editor.factory.ComponentFactory.EXPANDED_KEY
import ee.carlrobert.codegpt.toolwindow.chat.editor.factory.ComponentFactory.MIN_LINES_FOR_EXPAND
import ee.carlrobert.codegpt.toolwindow.chat.editor.state.EditorState
import ee.carlrobert.codegpt.toolwindow.chat.editor.state.EditorStateManager
import ee.carlrobert.codegpt.toolwindow.chat.parser.ReplaceWaiting
import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchReplace
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment

class ResponseEditorPanel(
    project: Project,
    item: Segment,
    readOnly: Boolean,
    disposableParent: Disposable,
) : BorderLayoutPanel(), Disposable {

    companion object {
        val RESPONSE_EDITOR_DIFF_VIEWER_KEY =
            Key.create<UnifiedDiffViewer?>("proxyai.responseEditorDiffViewer")
        val RESPONSE_EDITOR_DIFF_VIEWER_VALUE_PAIR_KEY =
            Key.create<Pair<String, String>>("proxyai.responseEditorDiffViewerValuePair")
        val RESPONSE_EDITOR_STATE_KEY = Key.create<EditorState>("proxyai.responseEditorState")
    }

    private val stateManager = project.service<EditorStateManager>()
    private var searchReplaceHandler: SearchReplaceHandler

    init {
        border = JBUI.Borders.empty(8, 0)
        isOpaque = false

        val state = stateManager.createFromSegment(item, readOnly)
        val editor = state.editor
        configureEditor(editor)
        searchReplaceHandler = SearchReplaceHandler(stateManager) { oldEditor, newEditor ->
            replaceEditor(oldEditor, newEditor)
        }

        addToCenter(editor.component)
        updateEditorUI()

        Disposer.register(disposableParent, this)
    }

    private fun configureEditor(editor: EditorEx) {
        editor.document.addDocumentListener(object : BulkAwareDocumentListener.Simple {
            override fun documentChanged(event: DocumentEvent) {
                runInEdt {
                    updateEditorUI()
                    if (editor.editorKind != EditorKind.DIFF) {
                        scrollToEnd()
                    }
                }
            }
        })
    }

    private fun updateEditorUI() {
        updateEditorHeightAndUI()
        updateExpandLinkVisibility()
    }

    override fun dispose() {
        val state = stateManager.getCurrentState()
        val editor = state?.editor ?: return
        val filePath = state.segment.filePath
        if (filePath != null) {
            DiffSyncManager.unregisterEditor(filePath, editor)
        }
    }

    fun handleSearchReplace(item: SearchReplace, partialResponse: Boolean) {
        searchReplaceHandler.handleSearchReplace(item, partialResponse)
    }

    fun handleReplace(item: ReplaceWaiting) {
        searchReplaceHandler.handleReplace(item)
    }

    fun getEditor(): EditorEx? {
        return stateManager.getCurrentState()?.editor
    }

    fun replaceEditor(oldEditor: EditorEx, newEditor: EditorEx) {
        runInEdt {
            val expanded = oldEditor.getUserData(EXPANDED_KEY) == true
            EXPANDED_KEY.set(newEditor, expanded)

            removeAll()

            configureEditor(newEditor)
            addToCenter(newEditor.component)

            ComponentFactory.updateEditorPreferredSize(newEditor, expanded)
            updateEditorUI()

            revalidate()
            repaint()
        }
    }

    fun removeEditorAndAuxiliaryPanels() {
        removeAll()
        revalidate()
        repaint()
    }

    private fun updateEditorHeightAndUI() {
        val editor = stateManager.getCurrentState()?.editor ?: return
        ComponentFactory.updateEditorPreferredSize(
            editor,
            editor.getUserData(EXPANDED_KEY) == true
        )
    }

    private fun updateExpandLinkVisibility() {
        val editor = stateManager.getCurrentState()?.editor ?: return
        if (componentCount == 0 || getComponent(0) !== editor.component) {
            return
        }

        val lineCount = editor.document.lineCount
        val shouldShowExpandLink = lineCount >= MIN_LINES_FOR_EXPAND

        val hasExpandLink = componentCount > 1 && getComponent(1) != null

        if (shouldShowExpandLink && !hasExpandLink) {
            val expandLinkPanel = ComponentFactory.createExpandLinkPanel(editor)
            addToBottom(expandLinkPanel)
            revalidate()
            repaint()
        } else if (!shouldShowExpandLink && hasExpandLink) {
            if (componentCount > 1) {
                remove(getComponent(1))
                revalidate()
                repaint()
            }
        }
    }

    private fun scrollToEnd() {
        val editor = stateManager.getCurrentState()?.editor ?: return
        val textLength = editor.document.textLength
        if (textLength > 0) {
            val logicalPosition = editor.offsetToLogicalPosition(textLength - 1)
            editor.caretModel.moveToOffset(textLength - 1)
            editor.scrollingModel.scrollTo(
                LogicalPosition(logicalPosition.line, 0),
                ScrollType.MAKE_VISIBLE
            )
        }
    }
}
