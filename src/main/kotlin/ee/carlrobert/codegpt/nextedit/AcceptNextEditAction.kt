package ee.carlrobert.codegpt.nextedit

import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import ee.carlrobert.codegpt.CodeGPTKeys

class AcceptNextEditAction : EditorAction(Handler()), HintManagerImpl.ActionToIgnore {

    companion object {
        const val ID = "codegpt.acceptNextEdit"
    }

    private class Handler : EditorWriteActionHandler() {

        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
            val diffViewer = CodeGPTKeys.EDITOR_PREDICTION_DIFF_VIEWER.get(editor) ?: return
            if (diffViewer.isVisible()) {
                diffViewer.applyChanges()
            }
        }

        override fun isEnabledForCaret(
            editor: Editor,
            caret: Caret,
            dataContext: DataContext
        ): Boolean {
            val diffViewer = CodeGPTKeys.EDITOR_PREDICTION_DIFF_VIEWER.get(editor)
            return diffViewer != null && diffViewer.isVisible()
        }
    }
}
