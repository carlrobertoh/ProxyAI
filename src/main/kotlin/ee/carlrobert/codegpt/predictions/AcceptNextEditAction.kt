package ee.carlrobert.codegpt.predictions

import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler

class AcceptNextEditAction : EditorAction(Handler()), HintManagerImpl.ActionToIgnore {

    companion object {
        const val ID = "codegpt.acceptNextEdit"
    }

    private class Handler : EditorWriteActionHandler() {

        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
            val navigator = NextEditSuggestionNavigator.NAVIGATOR_KEY.get(editor)
            if (navigator != null && navigator.isVisible()) {
                if (navigator.hasPreview()) navigator.acceptCurrent() else navigator.jumpToNext()
                return
            }
        }

        override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext): Boolean {
            val navigator = editor.getUserData(NextEditSuggestionNavigator.NAVIGATOR_KEY)
            return navigator != null && navigator.isVisible()
        }
    }
}
