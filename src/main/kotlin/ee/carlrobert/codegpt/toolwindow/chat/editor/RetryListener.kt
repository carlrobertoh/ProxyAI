package ee.carlrobert.codegpt.toolwindow.chat.editor

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DiffHeaderPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.state.EditorStateManager
import ee.carlrobert.codegpt.toolwindow.chat.parser.*
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.completion.CompletionEventListener
import okhttp3.sse.EventSource

class RetryListener(
    private val project: Project,
    private val messageParser: SseMessageParser,
    private val stateManager: EditorStateManager,
    private val onEditorReplaced: (EditorEx) -> Unit
) : CompletionEventListener<String> {

    private val logger = logger<RetryListener>()
    private var editorReplaced: Boolean = false

    override fun onOpen() {
        CompletionProgressNotifier.update(project, true)
    }

    override fun onMessage(message: String, eventSource: EventSource?) {
        processMessageSegments(message, eventSource)
    }

    override fun onError(error: ErrorDetails?, ex: Throwable?) {
        logger.error("Something went wrong while retrying diff-based editing", ex)
        handleComplete()
    }

    override fun onCancelled(messageBuilder: java.lang.StringBuilder?) {
        handleComplete()
    }

    override fun onComplete(messageBuilder: StringBuilder?) {
        handleComplete()
    }

    private fun processMessageSegments(
        message: String,
        eventSource: EventSource?
    ) {
        val segments = messageParser.parse(message)
        for (segment in segments) {
            when (segment) {
                is SearchReplace -> {
                    stateManager.getCurrentState()?.updateContent(segment)
                    eventSource?.cancel()
                    return
                }

                is SearchWaiting -> {}

                is ReplaceWaiting -> {
                    if (!editorReplaced) {
                        editorReplaced = true

                        val newState = stateManager.createFromSegment(segment)
                        onEditorReplaced(newState.editor)
                    }
                    handleReplace(segment)
                }

                is CodeEnd -> {
                    eventSource?.cancel()
                }

                else -> return
            }
        }
    }

    private fun handleReplace(item: ReplaceWaiting) {
        val currentState = stateManager.getCurrentState() ?: return
        val editor = currentState.editor
        (editor.permanentHeaderComponent as? DiffHeaderPanel)?.editing()

        currentState.updateContent(item)
    }

    private fun handleComplete() {
        val editor = stateManager.getCurrentState()?.editor ?: return
        (editor.permanentHeaderComponent as? DiffHeaderPanel)?.handleDone()
        CompletionProgressNotifier.update(project, false)
    }
}