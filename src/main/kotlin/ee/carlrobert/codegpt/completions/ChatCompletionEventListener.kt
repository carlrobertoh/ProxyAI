package ee.carlrobert.codegpt.completions

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.events.CodeGPTEvent
import ee.carlrobert.codegpt.telemetry.TelemetryAction

class ChatCompletionEventListener(
    private val project: Project,
    callParameters: ChatCompletionParameters,
    wrappedListener: CompletionResponseEventListener
) : BaseChatStreamEventListener(callParameters, wrappedListener, syncMessageResponse = true) {

    companion object {
        private val LOG = Logger.getInstance(ChatCompletionEventListener::class.java)
    }

    private val objectMapper = ObjectMapper()

    override fun onEvent(data: String) {
        try {
            val event = objectMapper.readValue(data, CodeGPTEvent::class.java)
            wrappedListener.handleCodeGPTEvent(event)
        } catch (exception: JsonProcessingException) {
            LOG.debug("Failed to parse CodeGPTEvent from data: $data", exception)
        }
    }

    override fun onComplete(messageBuilder: StringBuilder) {
        handleCompleted(messageBuilder)
    }

    override fun onCancelled(messageBuilder: StringBuilder) {
        handleCompleted(messageBuilder)
    }

    override fun onError(error: ChatError, ex: Throwable) {
        try {
            callParameters.conversation.addMessage(callParameters.message)
            wrappedListener.handleError(error, ex)
        } finally {
            sendError(error, ex)
        }
    }

    private fun handleCompleted(messageBuilder: StringBuilder) {
        CompletionProgressNotifier.update(project, false)
        wrappedListener.handleCompleted(messageBuilder.toString(), callParameters)
    }

    private fun sendError(error: ChatError, ex: Throwable) {
        val telemetryMessage = TelemetryAction.COMPLETION_ERROR.createActionMessage()
        if (error.code == "insufficient_quota") {
            telemetryMessage
                .property("type", "USER")
                .property("code", "INSUFFICIENT_QUOTA")
        } else {
            telemetryMessage
                .property("conversationId", callParameters.conversation.id.toString())
                .error(RuntimeException(error.toString(), ex))
        }
        telemetryMessage.send()
    }
}
