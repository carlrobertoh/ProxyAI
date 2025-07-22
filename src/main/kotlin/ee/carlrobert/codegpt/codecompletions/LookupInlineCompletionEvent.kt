package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiUtilBase

class LookupInlineCompletionEvent(private val event: LookupEvent) : InlineCompletionEvent {

    override fun toRequest(): InlineCompletionRequest? {
        val editor = runReadAction { event.lookup?.editor } ?: return null
        val caretModel = editor.caretModel
        if (caretModel.caretCount != 1) return null

        val project = editor.project ?: return null

        val (file, offset) = runReadAction {
            getPsiFile(caretModel.currentCaret, project) to caretModel.offset
        }
        if (file == null) return null

        return InlineCompletionRequest(
            this,
            file,
            editor,
            editor.document,
            offset,
            offset,
            event.item
        )
    }

    private fun getPsiFile(caret: Caret, project: Project): PsiFile? {
        return runReadAction {
            val file = PsiDocumentManager.getInstance(project).getPsiFile(caret.editor.document)
                ?: return@runReadAction null
            if (file.isLoadedInMemory()) {
                PsiUtilBase.getPsiFileInEditor(caret, project)
            } else {
                file
            }
        }
    }

    private fun PsiFile.isLoadedInMemory(): Boolean {
        return (this as? PsiFileImpl)?.treeElement != null
    }
}