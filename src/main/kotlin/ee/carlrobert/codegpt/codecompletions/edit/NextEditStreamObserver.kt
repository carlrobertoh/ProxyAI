package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.notification.NotificationAction.createSimpleExpiring
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.predictions.CodeSuggestionDiffViewer
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.service.NextEditResponse
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kotlin.coroutines.cancellation.CancellationException

class NextEditStreamObserver(
    private val editor: Editor,
    private val addToQueue: Boolean = false,
    private val onDispose: () -> Unit
) : StreamObserver<NextEditResponse> {

    companion object {
        private val logger = thisLogger()
    }

    override fun onNext(response: NextEditResponse) {
        if (addToQueue) {
            CodeGPTKeys.REMAINING_PREDICTION_RESPONSE.set(editor, response)
        } else {
            runInEdt {
                val documentText = editor.document.text
                if (LookupManager.getActiveLookup(editor) == null
                    && documentText != response.nextRevision
                    && documentText == response.oldRevision
                ) {
                    CodeSuggestionDiffViewer.displayInlineDiff(editor, response)
                }
            }
        }
    }

    override fun onError(ex: Throwable) {
        if (ex is CancellationException ||
            (ex is StatusRuntimeException && ex.status.code == Status.Code.CANCELLED)
        ) {
            onCompleted()
            return
        }

        try {
            if (ex is StatusRuntimeException) {
                OverlayUtil.showNotification(
                    ex.status.description ?: ex.localizedMessage,
                    NotificationType.ERROR,
                    createSimpleExpiring("Disable multi-line edits") {
                        service<CodeGPTServiceSettings>().state.nextEditsEnabled =
                            false
                    })
            } else {
                logger.error("Something went wrong", ex)
            }
        } finally {
            onCompleted()
            onDispose()
        }
    }

    override fun onCompleted() {
        editor.project?.let { CompletionProgressNotifier.update(it, false) }
    }
}