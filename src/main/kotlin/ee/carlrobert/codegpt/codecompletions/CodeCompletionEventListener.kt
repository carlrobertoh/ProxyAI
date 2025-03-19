package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import ee.carlrobert.codegpt.CodeGPTKeys.REMAINING_EDITOR_COMPLETION
import ee.carlrobert.codegpt.codecompletions.CompletionUtil.formatCompletion
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.ui.OverlayUtil.showNotification
import ee.carlrobert.codegpt.util.EditorUtil.adjustWhitespaces
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.completion.CompletionEventListener
import okhttp3.sse.EventSource
import kotlin.math.min

abstract class CodeCompletionEventListener(
    private val editor: Editor
) : CompletionEventListener<String> {

    companion object {
        private val logger = thisLogger()
    }

    abstract fun handleCompleted(messageBuilder: StringBuilder)

    override fun onOpen() {
        setLoading(true)
    }

    override fun onComplete(messageBuilder: StringBuilder) {
        setLoading(false)
        handleCompleted(messageBuilder)
    }

    override fun onCancelled(messageBuilder: StringBuilder) {
        setLoading(false)
        handleCompleted(messageBuilder)
    }

    override fun onError(error: ErrorDetails, ex: Throwable) {
        val isCodeGPTService = GeneralSettings.getSelectedService() == ServiceType.CODEGPT
        if (isCodeGPTService && "RATE_LIMIT_EXCEEDED" == error.code) {
            service<CodeGPTServiceSettings>().state
                .codeCompletionSettings
                .codeCompletionsEnabled = false
        }

        if (ex.message == null || (ex.message != null && ex.message != "Canceled")) {
            showNotification(error.message, NotificationType.ERROR)
            logger.error(error.message, ex)
        }
        setLoading(false)
    }

    private fun setLoading(loading: Boolean) {
        editor.project?.let {
            CompletionProgressNotifier.update(it, loading)
        }
    }
}

class CodeCompletionMultiLineEventListener(
    private val request: InlineCompletionRequest,
    private val onCompletionReceived: (String) -> Unit
) : CodeCompletionEventListener(request.editor) {

    override fun handleCompleted(messageBuilder: StringBuilder) {
        request.editor.project?.let { CompletionProgressNotifier.update(it, false) }
        runInEdt {
            onCompletionReceived(runWriteAction {
                messageBuilder.toString().formatCompletion(request)
            })
        }
    }
}

class CodeCompletionSingleLineEventListener(
    private val editor: Editor,
    private val infillRequest: InfillRequest,
    private val onSend: (element: CodeCompletionTextElement) -> Unit,
) : CodeCompletionEventListener(editor) {

    private var isFirstLine = true
    private val currentLineBuffer = StringBuilder()
    private val incomingTextBuffer = StringBuilder()

    override fun onMessage(message: String, eventSource: EventSource) {
        incomingTextBuffer.append(message)

        while (incomingTextBuffer.contains("\n")) {
            val lineEndIndex = incomingTextBuffer.indexOf("\n")
            val line = incomingTextBuffer.substring(0, lineEndIndex) + '\n'
            processCompletionLine(line)
            incomingTextBuffer.delete(0, lineEndIndex + 1)
        }
    }

    override fun handleCompleted(messageBuilder: StringBuilder) {
        if (incomingTextBuffer.isNotEmpty()) {
            appendRemainingCompletion(incomingTextBuffer.toString())
        }

        if (isFirstLine) {
            val completionLine = messageBuilder.toString().adjustWhitespaces(editor)
            REMAINING_EDITOR_COMPLETION.set(editor, completionLine)
            onLineReceived(completionLine)
        }
    }

    private fun processCompletionLine(line: String) {
        currentLineBuffer.append(line)

        if (currentLineBuffer.trim().isNotEmpty()) {
            val completionText = if (isFirstLine) {
                line.adjustWhitespaces(editor).also {
                    isFirstLine = false
                    onLineReceived(it)
                }
            } else {
                currentLineBuffer.toString()
            }

            appendRemainingCompletion(completionText)
            currentLineBuffer.clear()
        }
    }

    private fun onLineReceived(completionLine: String) {
        runInEdt {
            var editorLineSuffix = editor.getLineSuffixAfterCaret()
            if (editorLineSuffix.isBlank()) {
                onSend(
                    CodeCompletionTextElement(
                        completionLine,
                        infillRequest.caretOffset,
                        TextRange.from(infillRequest.caretOffset, completionLine.length),
                    )
                )
            } else {
                var caretShift = 0

                // TODO: Handle other scenarios
                val processedCompletion =
                    if (completionLine.startsWith(editorLineSuffix.first())) {
                        caretShift++
                        editorLineSuffix = editorLineSuffix.substring(1)
                        completionLine.substring(1)
                    } else {
                        completionLine
                    }

                val completionWithRemovedSuffix =
                    processedCompletion.removeSuffix(editorLineSuffix)

                onSend(
                    CodeCompletionTextElement(
                        completionWithRemovedSuffix,
                        infillRequest.caretOffset + caretShift,
                        TextRange.from(
                            infillRequest.caretOffset + caretShift,
                            completionWithRemovedSuffix.length
                        ),
                        caretShift,
                        completionLine
                    )
                )
            }
        }
    }

    private fun appendRemainingCompletion(text: String) {
        val previousRemainingText = REMAINING_EDITOR_COMPLETION.get(editor) ?: ""
        REMAINING_EDITOR_COMPLETION.set(editor, previousRemainingText + text)
    }

    private fun Editor.getLineSuffixAfterCaret(): String {
        val lineEndOffset = document.getLineEndOffset(document.getLineNumber(caretModel.offset))
        return document.getText(
            TextRange(
                caretModel.offset,
                min(lineEndOffset + 1, document.textLength)
            )
        )
    }
}
