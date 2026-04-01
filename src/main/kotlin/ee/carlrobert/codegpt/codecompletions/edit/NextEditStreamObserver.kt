package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.nextedit.NextEditDiffViewer
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.service.NextEditResponse
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver

class NextEditStreamObserver(
    private val editor: Editor,
    private val addToQueue: Boolean = false,
    private val onRefreshConnection: () -> Unit
) : StreamObserver<NextEditResponse> {

    companion object {
        private val logger = thisLogger()
    }

    override fun onNext(response: NextEditResponse) {
        if (ConfigurationSettings.getState().debugModeEnabled) {
            logger.info("Next-edit gRPC response: $response")
        }
        if (addToQueue) {
            CodeGPTKeys.REMAINING_NEXT_EDITS.set(editor, response)
        } else {
            runInEdt {
                val documentText = editor.document.text
                if (LookupManager.getActiveLookup(editor) == null
                    && documentText != response.nextRevision
                    && documentText == response.oldRevision
                ) {
                    NextEditDiffViewer.displayNextEdit(editor, response)
                }
            }
        }
    }

    override fun onError(ex: Throwable) {
        if (ex is StatusRuntimeException && ex.status.code == Status.Code.UNAVAILABLE) {
            onRefreshConnection()
        }

        onCompleted()
    }

    override fun onCompleted() {
        if (ConfigurationSettings.getState().debugModeEnabled) {
            logger.info("Next-edit gRPC stream completed")
        }
        editor.project?.let { CompletionProgressNotifier.update(it, false) }
    }
}
