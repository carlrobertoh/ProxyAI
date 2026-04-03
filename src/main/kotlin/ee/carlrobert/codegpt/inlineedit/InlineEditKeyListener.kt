package ee.carlrobert.codegpt.inlineedit

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import ee.carlrobert.codegpt.CodeGPTKeys

/**
 * Keyboard shortcuts for Inline Edit diff feature.
 */
class AcceptAllInlineEditAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val session =
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
        if (session != null) {
            session.acceptAll()
        } else {
            acceptAll(editor)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)

        e.presentation.isEnabledAndVisible =
            project != null && editor != null &&
                    (editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER) != null
                            || hasActiveInlineEdit(editor))
    }

    companion object {
        fun acceptAll(editor: Editor) {
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.acceptAll()
                ?: editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)?.acceptAll()
        }

        fun hasActiveInlineEdit(editor: Editor): Boolean =
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION) != null ||
                    editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER) != null
    }
}

class RejectAllInlineEditAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val session =
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
        if (session != null) {
            session.rejectAll()
        } else {
            rejectAll(editor)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)

        e.presentation.isEnabledAndVisible = project != null && editor != null
                && AcceptAllInlineEditAction.hasActiveInlineEdit(editor)
    }

    companion object {
        fun rejectAll(editor: Editor) {
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.rejectAll()
                ?: editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)?.rejectAll()
        }
    }
}

class RejectInlineEditAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)?.rejectNext()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)

        e.presentation.isEnabledAndVisible = project != null && editor != null &&
                (editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER) != null
                        || AcceptAllInlineEditAction.hasActiveInlineEdit(editor))
    }
}

class AcceptCurrentInlineEditAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)?.acceptNext()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)

        e.presentation.isEnabledAndVisible = project != null && editor != null &&
                (editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER) != null
                        || AcceptAllInlineEditAction.hasActiveInlineEdit(editor))
    }
}

class RejectCurrentInlineEditAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)?.rejectNext()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)

        e.presentation.isEnabledAndVisible = project != null && editor != null &&
                (editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER) != null
                        || AcceptAllInlineEditAction.hasActiveInlineEdit(editor))
    }
}
