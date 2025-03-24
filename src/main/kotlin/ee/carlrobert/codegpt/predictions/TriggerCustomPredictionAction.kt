package ee.carlrobert.codegpt.predictions

import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.ui.OverlayUtil

class TriggerCustomPredictionAction : EditorAction(Handler()), HintManagerImpl.ActionToIgnore {

    companion object {
        const val ID = "codegpt.triggerCustomPrediction"
    }

    private class Handler : EditorWriteActionHandler() {

        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
            if (GeneralSettings.getSelectedService() != ServiceType.CODEGPT) {
                return
            }

            if (!service<CodeGPTServiceSettings>().state.nextEditsEnabled) {
                val notification = OverlayUtil.getDefaultNotification(
                    "Please enable multi-line edits before using this feature.",
                    NotificationType.WARNING,
                )
                notification.addAction(object : AnAction("Enable Multi-Line Edits") {
                    override fun actionPerformed(e: AnActionEvent) {
                        service<CodeGPTServiceSettings>().state.nextEditsEnabled = true
                        notification.hideBalloon()
                    }
                })
                OverlayUtil.notify(notification)

                return
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                service<PredictionService>().displayInlineDiff(editor, true)
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