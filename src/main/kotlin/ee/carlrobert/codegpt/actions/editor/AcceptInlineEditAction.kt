package ee.carlrobert.codegpt.actions.editor

import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import ee.carlrobert.codegpt.CodeGPTKeys

class AcceptInlineEditAction : EditorAction(Handler()), HintManagerImpl.ActionToIgnore {

    companion object {
        const val ID = "codegpt.acceptInlineEdit"
    }

    private class Handler : EditorWriteActionHandler() {

        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.let { session ->
                session.acceptNearestToCaret()
                return
            }

            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)?.acceptNext()
        }

        override fun isEnabledForCaret(
            editor: Editor,
            caret: Caret,
            dataContext: DataContext
        ): Boolean {
            return editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION) != null ||
                    editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER) != null
        }
    }
}
