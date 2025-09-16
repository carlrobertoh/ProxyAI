package ee.carlrobert.codegpt.actions.editor

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid
import com.intellij.ui.awt.RelativePoint
import ee.carlrobert.codegpt.CodeGPTBundle
import java.awt.MouseInfo

class ShowEditorActionGroupAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val actionManager = ActionManager.getInstance()
        val actionGroup = actionManager.getAction("action.editor.group.EditorActionGroup")
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                CodeGPTBundle.get("project.label"), (actionGroup as ActionGroup), e.dataContext,
                ActionSelectionAid.ALPHA_NUMBERING, true
            )
            .show(RelativePoint(MouseInfo.getPointerInfo().location))
    }
}