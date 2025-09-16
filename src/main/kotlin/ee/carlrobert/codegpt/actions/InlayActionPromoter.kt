
package ee.carlrobert.codegpt.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.predictions.OpenPredictionAction
import ee.carlrobert.codegpt.predictions.TriggerCustomPredictionAction
import ee.carlrobert.codegpt.inlineedit.AcceptCurrentInlineEditAction
import ee.carlrobert.codegpt.actions.editor.AcceptInlineEditAction
import ee.carlrobert.codegpt.inlineedit.RejectCurrentInlineEditAction

class InlayActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val editor = CommonDataKeys.EDITOR.getData(context) ?: return emptyList()
        val hasInlineEdit = editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION) != null ||
                editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER) != null
        if (hasInlineEdit) {
            actions.filterIsInstance<AcceptInlineEditAction>().takeIf { it.isNotEmpty() }?.let { return it }
            actions.filterIsInstance<AcceptCurrentInlineEditAction>().takeIf { it.isNotEmpty() }?.let { return it }
            actions.filterIsInstance<RejectCurrentInlineEditAction>().takeIf { it.isNotEmpty() }?.let { return it }
        }

        actions.filterIsInstance<TriggerCustomPredictionAction>().takeIf { it.isNotEmpty() }?.let { return it }
        actions.filterIsInstance<OpenPredictionAction>().takeIf { it.isNotEmpty() }?.let { return it }

        return emptyList()
    }
}
