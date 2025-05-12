package ee.carlrobert.codegpt

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorKind
import ee.carlrobert.codegpt.codecompletions.CodeCompletionService
import ee.carlrobert.codegpt.codecompletions.LookupInlineCompletionEvent

class CodeGPTLookupListener : LookupManagerListener {
    override fun activeLookupChanged(oldLookup: Lookup?, newLookup: Lookup?) {
        if (newLookup is LookupImpl) {
            newLookup.addLookupListener(object : LookupListener {

                var beforeApply: String = ""
                var cursorOffset: Int = 0

                override fun beforeItemSelected(event: LookupEvent): Boolean {
                    beforeApply = newLookup.editor.document.text
                    cursorOffset = runReadAction {
                        newLookup.editor.caretModel.offset
                    }

                    return true
                }

                override fun itemSelected(event: LookupEvent) {
                    val editor = newLookup.editor
                    val project = editor.project ?: return

                    if (!project.service<CodeCompletionService>().isCodeCompletionsEnabled()
                        || editor.editorKind != EditorKind.MAIN_EDITOR
                    ) {
                        return
                    }

                    InlineCompletion.getHandlerOrNull(editor)?.invokeEvent(
                        LookupInlineCompletionEvent(event)
                    )
                }
            })
        }
    }
}