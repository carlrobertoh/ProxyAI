package ee.carlrobert.codegpt.predictions

import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import com.intellij.util.application
import ee.carlrobert.codegpt.codecompletions.edit.NextEditCoordinator
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import kotlin.coroutines.cancellation.CancellationException

class TriggerCompletionAction : EditorAction(Handler()), HintManagerImpl.ActionToIgnore {

    companion object {
        const val ID = "codegpt.triggerCompletion"
        private val logger = thisLogger()
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

            try {
                application.executeOnPooledThread {
                    NextEditCoordinator.requestNextEdit(
                        editor,
                        editor.document.text,
                        runReadAction { editor.caretModel.offset },
                        false
                    )
                }
            } catch (_: CancellationException) {
                return
            } catch (ex: Exception) {
                logger.error("Error communicating with server: ${ex.message}")
            }
        }

        override fun isEnabledForCaret(
            editor: Editor,
            caret: Caret,
            dataContext: DataContext
        ): Boolean {
            return editor.getUserData(NextEditSuggestionNavigator.NAVIGATOR_KEY) == null
        }
    }
}
