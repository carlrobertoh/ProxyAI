package ee.carlrobert.codegpt.actions.editor

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.ui.JBColor
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.util.ThinkingOutputParser
import ee.carlrobert.codegpt.ui.ObservableProperties
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.completion.CompletionEventListener
import okhttp3.sse.EventSource

class EditCodeCompletionListener(
    private val editor: Editor,
    private val observableProperties: ObservableProperties,
    private val selectionTextRange: TextRange
) : CompletionEventListener<String> {

    private var replacedLength = 0
    private var currentHighlighter: RangeHighlighter? = null
    private val thinkingOutputParser = ThinkingOutputParser()

    override fun onMessage(message: String, eventSource: EventSource) {
        val processedChunk = thinkingOutputParser.processChunk(message)
        if (processedChunk.isNotEmpty() && thinkingOutputParser.isFinished) {
            runInEdt { handleDiff(processedChunk) }
        }
    }

    override fun onComplete(messageBuilder: StringBuilder) {
        runInEdt {
            if (replacedLength == 0 && messageBuilder.isNotEmpty()) {
                handleDiff(messageBuilder.toString())
            }
            cleanupAndFormat()
        }
        stopLoading()
    }

    override fun onError(error: ErrorDetails, ex: Throwable) {
        OverlayUtil.showNotification(
            error.message,
            NotificationType.ERROR,
            NotificationAction.createSimpleExpiring("Upgrade plan") {
                BrowserUtil.open("https://tryproxy.io/#pricing")
            },
        )
        stopLoading()
    }

    private fun stopLoading() {
        observableProperties.loading.set(false)
        editor.project?.let {
            CompletionProgressNotifier.update(it, false)
        }
    }

    private fun updateHighlighter(editor: Editor) {
        cleanupHighlighter()

        val document = editor.document
        val lineNumber = document.getLineNumber(editor.caretModel.offset)
        currentHighlighter = editor.markupModel.addRangeHighlighter(
            document.getLineStartOffset(lineNumber),
            document.getLineEndOffset(lineNumber),
            HighlighterLayer.SELECTION - 1,
            TextAttributes().apply {
                effectType = EffectType.BOXED
                effectColor =
                    JBColor.namedColor("PsiViewer.referenceHighlightColor", 0x6A7B15)
                errorStripeColor = effectColor
            },
            HighlighterTargetArea.EXACT_RANGE
        )
    }

    private fun handleDiff(message: String) {
        val document = editor.document
        val startOffset = selectionTextRange.startOffset
        val endOffset = selectionTextRange.endOffset
        runUndoTransparentWriteAction {
            val remainingOriginalLength = endOffset - (startOffset + replacedLength)
            if (remainingOriginalLength > 0) {
                document.replaceString(
                    startOffset + replacedLength,
                    startOffset + replacedLength + minOf(
                        message.length,
                        remainingOriginalLength
                    ),
                    StringUtil.convertLineSeparators(message)
                )
            } else {
                document.insertString(startOffset + replacedLength, message)
            }
        }

        replacedLength += message.length
        editor.caretModel.moveToOffset(startOffset + replacedLength)
        updateHighlighter(editor)
    }

    private fun cleanupAndFormat() {
        val project = editor.project ?: return
        val document = editor.document
        val psiDocumentManager = project.service<PsiDocumentManager>()
        val psiFile = psiDocumentManager.getPsiFile(document) ?: return
        val startOffset = selectionTextRange.startOffset
        val endOffset = selectionTextRange.endOffset
        val newEndOffset = startOffset + replacedLength

        runWriteCommandAction(project) {
            if (newEndOffset < endOffset) {
                document.deleteString(newEndOffset, endOffset)
            }
            psiDocumentManager.commitDocument(document)
            project.service<CodeStyleManager>().reformatText(
                psiFile,
                listOf(TextRange(startOffset, newEndOffset))
            )
        }

        editor.caretModel.moveToOffset(newEndOffset)
        psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
        cleanupHighlighter()
    }

    private fun cleanupHighlighter() {
        currentHighlighter?.let { editor.markupModel.removeHighlighter(it) }
        currentHighlighter = null
    }
}