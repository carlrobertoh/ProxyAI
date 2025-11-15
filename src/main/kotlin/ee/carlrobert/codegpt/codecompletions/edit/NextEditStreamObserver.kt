package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.nextedit.NextEditDiffViewer
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
        if (ex is CancellationException ||
            (ex is StatusRuntimeException && ex.status.code == Status.Code.CANCELLED)
        ) {
            onCompleted()
            return
        }

        try {
            if (ex is StatusRuntimeException) {
                if (ex.status.code == Status.Code.DEADLINE_EXCEEDED) {
                    return
                }
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
