package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.codegpt.predictions.CodeSuggestionDiffViewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CodeCompletionInsertHandler : InlineCompletionInsertHandler {

    override fun afterInsertion(
        environment: InlineCompletionInsertEnvironment,
        elements: List<InlineCompletionElement>
    ) {
        val editor = environment.editor
        val remainingCompletion = CodeGPTKeys.REMAINING_CODE_COMPLETION.get(editor)
        if (remainingCompletion != null && remainingCompletion.partialCompletion.isNotEmpty()) {
            InlineCompletion.getHandlerOrNull(editor)?.invoke(
                InlineCompletionEvent.DirectCall(editor, editor.caretModel.currentCaret)
            )
            val caretOffset = runReadAction { editor.caretModel.offset }
            val prefix = editor.document.text.substring(0, caretOffset)
            val suffix = editor.document.text.substring(caretOffset)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                editor.project?.service<GrpcClientService>()
                    ?.getNextEdit(
                        editor,
                        prefix + remainingCompletion.partialCompletion + suffix,
                        caretOffset + remainingCompletion.partialCompletion.length,
                        true
                    )
            }
            return
        } else {
            if (CodeGPTKeys.REMAINING_PREDICTION_RESPONSE.get(editor) == null) {
                val caretOffset = runReadAction { editor.caretModel.offset }
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    editor.project?.service<GrpcClientService>()?.getNextEdit(
                        editor,
                        editor.document.text,
                        caretOffset,
                    )
                }
                return
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val queuedPrediction = CodeGPTKeys.REMAINING_PREDICTION_RESPONSE.get(editor)
            if (queuedPrediction != null) {
                runInEdt {
                    CodeSuggestionDiffViewer.displayInlineDiff(editor, queuedPrediction)
                }
            }
        }
    }
}