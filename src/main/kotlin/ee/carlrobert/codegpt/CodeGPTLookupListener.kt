package ee.carlrobert.codegpt

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorKind
import ee.carlrobert.codegpt.codecompletions.CodeCompletionService

class CodeGPTLookupListener : LookupManagerListener {
    override fun activeLookupChanged(oldLookup: Lookup?, newLookup: Lookup?) {
        if (newLookup is LookupImpl) {
            newLookup.addLookupListener(object : LookupListener {
                override fun beforeItemSelected(event: LookupEvent): Boolean {
                    return true
                }

                override fun itemSelected(event: LookupEvent) {
                    val editor = newLookup.editor
                    if (!service<CodeCompletionService>().isCodeCompletionsEnabled()
                        || editor.editorKind != EditorKind.MAIN_EDITOR
                    ) {
                        return
                    }

                    InlineCompletion.getHandlerOrNull(editor)?.invokeEvent(
                        InlineCompletionEvent.DirectCall(editor, editor.caretModel.currentCaret)
                    )
                }
            })
        }
    }
}
