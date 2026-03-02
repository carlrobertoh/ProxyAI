package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.completions.CompletionError
import ee.carlrobert.codegpt.completions.CompletionStreamEventListener
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.service.PartialCodeCompletionResponse
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver

class CodeCompletionStreamObserver(
    private val editor: Editor,
    private val eventListener: CompletionStreamEventListener,
) : StreamObserver<PartialCodeCompletionResponse> {

    companion object {
        private val logger = thisLogger()
    }

    private val messageBuilder = StringBuilder()
    override fun onNext(value: PartialCodeCompletionResponse) {
        CodeGPTKeys.LAST_COMPLETION_RESPONSE_ID.set(editor, value.id)
        messageBuilder.append(value.partialCompletion)
        eventListener.onMessage(value.partialCompletion)
    }

    override fun onError(t: Throwable?) {
        if (t is StatusRuntimeException) {
            val code = t.status.code
            if (code == Status.Code.CANCELLED || code == Status.Code.DEADLINE_EXCEEDED) {
                eventListener.onComplete(messageBuilder)
                return
            }

            if (code == Status.Code.UNAVAILABLE) {
                eventListener.onError(CompletionError("Connection unavailable"), t)
                return
            }
        }

        logger.error("Unexpected error occurred while fetching code completion", t)
        OverlayUtil.showNotification(
            t?.message ?: "Something went wrong",
            NotificationType.ERROR
        )
        eventListener.onError(
            CompletionError(t?.message ?: "Code completion error"),
            t ?: Throwable()
        )
    }

    override fun onCompleted() {
        eventListener.onComplete(messageBuilder)
    }
}
