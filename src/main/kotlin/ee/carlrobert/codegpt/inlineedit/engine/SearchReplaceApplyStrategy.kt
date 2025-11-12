package ee.carlrobert.codegpt.inlineedit.engine

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.util.TextRange
import ee.carlrobert.codegpt.inlineedit.InlineEditSearchReplaceListener
import ee.carlrobert.codegpt.completions.CompletionRequestService
import ee.carlrobert.codegpt.completions.InlineEditCompletionParameters
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.conversations.Conversation

class SearchReplaceApplyStrategy : ApplyStrategy {
    override fun apply(ctx: ApplyContext) {
        val editor = ctx.editor
        val inlay = ctx.inlay

        runInEdt {
            inlay.setInlineEditControlsVisible(false)
            inlay.setThinkingVisible(true)
        }

        val file = editor.virtualFile
        val parameters = InlineEditCompletionParameters(
            ctx.promptText,
            editor.selectionModel.selectedText,
            file?.path,
            file?.extension,
            editor.project?.basePath,
            null,
            null,
            ctx.submissionHandler.getSessionConversation(),
            null,
            null,
            editor.caretModel.offset
        )

        val requestId = System.nanoTime()
        editor.putUserData(InlineEditSearchReplaceListener.REQUEST_ID_KEY, requestId)

        val listener = InlineEditSearchReplaceListener(
            editor,
            inlay.observableProperties,
            TextRange(editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd),
            requestId,
            ctx.submissionHandler.getSessionConversation()
        )
        editor.putUserData(InlineEditSearchReplaceListener.LISTENER_KEY, listener)

        try {
            service<CompletionRequestService>().getInlineEditCompletionAsync(parameters, listener)
        } catch (_: Exception) {
            runInEdt {
                inlay.setThinkingVisible(false)
            }
        }
    }
}
