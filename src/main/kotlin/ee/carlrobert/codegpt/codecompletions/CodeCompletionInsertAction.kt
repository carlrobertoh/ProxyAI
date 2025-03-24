package ee.carlrobert.codegpt.codecompletions

import ai.grazie.nlp.utils.takeWhitespaces
import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.session.InlineCompletionContext
import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.ThreadingAssertions
import ee.carlrobert.codegpt.CodeGPTKeys.REMAINING_EDITOR_COMPLETION
import ee.carlrobert.codegpt.predictions.PredictionService
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings

class CodeCompletionInsertAction :
    EditorAction(InsertInlineCompletionHandler()), HintManagerImpl.ActionToIgnore {

    class InsertInlineCompletionHandler : EditorWriteActionHandler() {
        override fun executeWriteAction(editor: Editor, caret: Caret?, dataContext: DataContext) {
            ThreadingAssertions.assertEventDispatchThread()
            ThreadingAssertions.assertWriteAccess()

            val session = InlineCompletionSession.getOrNull(editor) ?: return
            val context = session.context
            val elements = context.state.elements
                .filter { it.element is CodeCompletionTextElement }
                .map { it.element as CodeCompletionTextElement }

            if (elements.isEmpty()) {
                val textToInsert = context.textToInsert()
                val remainingCompletion = REMAINING_EDITOR_COMPLETION.get(editor) ?: ""
                if (remainingCompletion.isNotEmpty()) {
                    REMAINING_EDITOR_COMPLETION.set(
                        editor,
                        remainingCompletion.removePrefix(textToInsert)
                    )
                }

                InlineCompletion.getHandlerOrNull(editor)?.insert()

                if (GeneralSettings.getSelectedService() == ServiceType.CODEGPT
                    && service<CodeGPTServiceSettings>().state.nextEditsEnabled
                ) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        service<PredictionService>().displayInlineDiff(editor)
                    }
                    return
                }
            }

            for (element in elements) {
                val insertEnvironment = InlineCompletionInsertEnvironment(
                    editor,
                    session.request.file,
                    element.textRange
                )
                context.copyUserDataTo(insertEnvironment)

                editor.document.insertString(element.textRange.startOffset, element.text)

                if (element.originalText == element.text) {
                    processStandardCompletionElement(element, editor)
                } else {
                    processPartialCompletionElement(element, editor)
                }

                PsiDocumentManager.getInstance(session.request.file.project)
                    .commitDocument(editor.document)

                session.provider.insertHandler.afterInsertion(insertEnvironment, elements)

                LookupManager.getActiveLookup(editor)?.hideLookup(false)
            }
        }

        override fun isEnabledForCaret(
            editor: Editor,
            caret: Caret,
            dataContext: DataContext
        ): Boolean {
            val completionContext = InlineCompletionContext.getOrNull(editor)
            val element = completionContext?.state?.elements?.firstOrNull()?.element
            if (element is CodeCompletionTextElement) {
                return completionContext.startOffset() == (caret.offset + element.offsetDelta)
            }
            return completionContext?.startOffset() == caret.offset
        }

        private fun processStandardCompletionElement(
            element: CodeCompletionTextElement,
            editor: Editor
        ) {
            val endOffset = element.textRange.endOffset
            editor.caretModel.moveToOffset(endOffset)

            val remainingCompletionLine = (REMAINING_EDITOR_COMPLETION.get(editor) ?: "")
                .removePrefix(element.text)

            processRemainingCompletion(remainingCompletionLine, editor, endOffset)
        }

        private fun processPartialCompletionElement(
            element: CodeCompletionTextElement,
            editor: Editor
        ) {
            val lineNumber = editor.document.getLineNumber(editor.caretModel.offset)
            val lineEndOffset = editor.document.getLineEndOffset(lineNumber)
            editor.caretModel.moveToOffset(lineEndOffset)

            val remainingText = REMAINING_EDITOR_COMPLETION.get(editor) ?: ""
            val remainingCompletionLine = if (element.originalText.length > remainingText.length) {
                remainingText.removePrefix(element.text)
            } else {
                remainingText.removePrefix(element.originalText)
            }

            processRemainingCompletion(remainingCompletionLine, editor, lineEndOffset + 1)
        }

        private fun processRemainingCompletion(
            remainingCompletion: String,
            editor: Editor,
            offset: Int
        ) {
            val whitespaces = remainingCompletion.takeWhitespaces()
            if (whitespaces.isNotEmpty()) {
                editor.document.insertString(offset, whitespaces)
                editor.caretModel.moveToOffset(offset + whitespaces.length)
            }

            val nextCompletionLine = remainingCompletion.removePrefix(whitespaces)
            REMAINING_EDITOR_COMPLETION.set(editor, nextCompletionLine)
        }
    }
}