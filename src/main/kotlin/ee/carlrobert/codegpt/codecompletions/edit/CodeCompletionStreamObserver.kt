package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.editor.Editor
import ee.carlrobert.codegpt.CodeGPTKeys
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.codecompletions.CodeCompletionEventListener
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.service.PartialCodeCompletionResponse
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.channels.ProducerScope
import okhttp3.Request
import okhttp3.sse.EventSource

class CodeCompletionStreamObserver(
    private val editor: Editor,
    private val channel: ProducerScope<InlineCompletionElement>,
    private val eventListener: CodeCompletionEventListener,
) : StreamObserver<PartialCodeCompletionResponse> {

    companion object {
        private val logger = thisLogger()
    }

    private val messageBuilder = StringBuilder()
    private val emptyEventSource = object : EventSource {
        override fun cancel() {
        }

        override fun request(): Request {
            return Request.Builder().build()
        }
    }

    override fun onNext(value: PartialCodeCompletionResponse) {
        CodeGPTKeys.LAST_COMPLETION_RESPONSE_ID.set(editor, value.id)
        messageBuilder.append(value.partialCompletion)
        eventListener.onMessage(value.partialCompletion, emptyEventSource)
    }

    override fun onError(t: Throwable?) {
        if (t is StatusRuntimeException && t.status.code == Status.Code.CANCELLED) {
            eventListener.onCancelled(messageBuilder)
            channel.close()
            return
        }
        logger.error("Error occurred while fetching code completion", t)
        if (t is StatusRuntimeException) {
            val code = t.status.code
            if (code != Status.Code.UNAVAILABLE && code != Status.Code.DEADLINE_EXCEEDED) {
                OverlayUtil.showNotification(
                    t.message ?: "Something went wrong",
                    NotificationType.ERROR
                )
            }
        } else {
            OverlayUtil.showNotification(
                t?.message ?: "Something went wrong",
                NotificationType.ERROR
            )
        }
        eventListener.onError(ErrorDetails(t?.message ?: "Code completion error"), t ?: Throwable())
        channel.close(t)
    }

    override fun onCompleted() {
        eventListener.onComplete(messageBuilder)
    }
}
