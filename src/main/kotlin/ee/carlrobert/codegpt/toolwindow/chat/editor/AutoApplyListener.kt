package ee.carlrobert.codegpt.toolwindow.chat.editor

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DiffHeaderPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.state.EditorStateManager
import ee.carlrobert.codegpt.toolwindow.chat.parser.*
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.completion.CompletionEventListener
import okhttp3.sse.EventSource

class AutoApplyListener(
    private val project: Project,
    private val stateManager: EditorStateManager,
    private val virtualFile: VirtualFile,
    private val originalSuggestion: String,
    private val onEditorReplaced: (EditorEx, EditorEx) -> Unit
) : CompletionEventListener<String> {

    private val logger = logger<AutoApplyListener>()
    private var editorReplaced: Boolean = false
    private val messageParser = SseMessageParser()
    private var eventSource: EventSource? = null

    override fun onOpen() {
        CompletionProgressNotifier.update(project, true)
    }

    override fun onMessage(message: String, eventSource: EventSource?) {
        if (this.eventSource == null && eventSource != null) {
            this.eventSource = eventSource
        }
        processMessageSegments(message)
    }

    override fun onError(error: ErrorDetails?, ex: Throwable?) {
        logger.error("Something went wrong while applying the changes", ex)
        ErrorHandler.handleError(error, ex)
        handleComplete()
    }

    override fun onCancelled(messageBuilder: java.lang.StringBuilder?) {
        handleComplete()
    }

    override fun onComplete(messageBuilder: StringBuilder?) {
        handleComplete()
    }

    private fun processMessageSegments(message: String) {
        val segments = messageParser.parse(message)
        for (segment in segments) {
            when (segment) {
                is SearchReplace -> {
                    updateContent(segment)
                }

                is SearchWaiting -> {}

                is ReplaceWaiting -> {
                    if (!editorReplaced) {
                        editorReplaced = true
                        createDiffEditor(segment)
                    }

                    updateContent(segment)
                }

                is CodeEnd -> {}

                else -> {}
            }
        }
    }

    private fun updateContent(segment: Segment) {
        val currentState = stateManager.getCurrentState()
        currentState?.updateContent(segment)
    }

    private fun createDiffEditor(segment: ReplaceWaiting) {
        val oldEditor = stateManager.getCurrentState()?.editor ?: return
        val currentText = EditorUtil.getFileContent(virtualFile)
        val containsText = currentText.contains(segment.search.trim())

        val newState = if (containsText) {
            stateManager.createFromSegment(segment, false, eventSource, originalSuggestion)
        } else {
            stateManager.transitionToFailedDiffState(
                segment.search,
                segment.replace,
                virtualFile
            ) ?: return
        }

        onEditorReplaced(oldEditor, newState.editor)
    }

    private fun handleComplete() {
        val editor = stateManager.getCurrentState()?.editor ?: return
        (editor.permanentHeaderComponent as? DiffHeaderPanel)?.handleDone()
        CompletionProgressNotifier.update(project, false)
        eventSource = null
    }

}
