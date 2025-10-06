package ee.carlrobert.codegpt.predictions

import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType

class TriggerCustomPredictionAction : EditorAction(Handler()), HintManagerImpl.ActionToIgnore {

    companion object {
        const val ID = "codegpt.triggerCustomPrediction"
    }

    private class Handler : EditorWriteActionHandler() {

        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
            val nextEditModelProvider = ModelSelectionService.getInstance()
                .getServiceForFeature(FeatureType.NEXT_EDIT)
            if (!listOf(
                    ServiceType.PROXYAI,
                    ServiceType.INCEPTION
                ).contains(nextEditModelProvider)
            ) {
                return
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                service<PredictionService>().displayInlineDiff(editor)
            }
        }

        override fun isEnabledForCaret(
            editor: Editor,
            caret: Caret,
            dataContext: DataContext
        ): Boolean {
            return editor.getUserData(CodeGPTKeys.EDITOR_PREDICTION_DIFF_VIEWER) == null
        }
    }
}