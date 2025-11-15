package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.codegpt.nextedit.NextEditCoordinator
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
        val completion = elements.first().text
        acceptCompletion(completion, editor)

        val caretOffset = runReadAction { editor.caretModel.offset }
        requestNextEditAsync(editor, caretOffset)
    }

    private fun acceptCompletion(completion: String, editor: Editor) {
        val caret = runReadAction { editor.caretModel.offset }
        val start = (caret - completion.length).coerceAtLeast(0)
        CodeGPTKeys.RECENT_COMPLETION_TEXT.set(editor, completion)
        CodeGPTKeys.RECENT_COMPLETION_RANGE.set(editor, TextRange(start, caret))

        val responseId = CodeGPTKeys.LAST_COMPLETION_RESPONSE_ID.get(editor)
        if (responseId != null) {
            editor.project?.service<GrpcClientService>()
                ?.acceptCodeCompletion(responseId, completion)
            CodeGPTKeys.LAST_COMPLETION_RESPONSE_ID.set(editor, null)
        }
    }

    private fun requestNextEditAsync(editor: Editor, caretOffset: Int) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            NextEditCoordinator.requestNextEdit(
                editor,
                editor.document.text,
                caretOffset,
                false
            )
        }
    }
}
