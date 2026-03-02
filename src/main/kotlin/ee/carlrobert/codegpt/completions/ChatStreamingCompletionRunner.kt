package ee.carlrobert.codegpt.completions

import ai.koog.prompt.streaming.StreamFrame
import ee.carlrobert.codegpt.util.ReasoningFrameTextAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object ChatStreamingCompletionRunner : CompletionRunner {

    override fun run(request: CompletionRunnerRequest): CancellableRequest {
        val chatRequest = request as? CompletionRunnerRequest.Chat
            ?: throw IllegalArgumentException("ChatStreamingCompletionRunner can only run Chat requests")
        return executeAsync(chatRequest)
    }

    private fun executeAsync(request: CompletionRunnerRequest.Chat): CancellableRequest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cancelled = AtomicBoolean(false)
        val jobRef = AtomicReference<Job?>()
        val cancellableRequest = CancellableRequest {
            cancelled.set(true)
            jobRef.get()?.cancel(CancellationException("Cancelled by user"))
        }

        request.eventListener.onOpen()
        val job = scope.launch {
            val messageBuilder = StringBuilder()
            try {
                streamOrFallback(request, messageBuilder)

                if (cancelled.get()) {
                    request.eventListener.onCancelled(StringBuilder(messageBuilder))
                    return@launch
                }
                request.eventListener.onComplete(StringBuilder(messageBuilder))
            } catch (_: CancellationException) {
                request.eventListener.onCancelled(StringBuilder(messageBuilder))
            } catch (exception: Throwable) {
                request.eventListener.onError(
                    ChatError(exception.message ?: "Failed to complete request"),
                    exception
                )
            } finally {
                runCatching { request.executor.close() }
                scope.cancel()
            }
        }
        jobRef.set(job)

        return cancellableRequest
    }

    private suspend fun streamOrFallback(
        request: CompletionRunnerRequest.Chat,
        messageBuilder: StringBuilder
    ) {
        val frameAdapter = ReasoningFrameTextAdapter()

        runCatching {
            request.executor.executeStreaming(request.prompt, request.model, emptyList())
                .collect { frame ->
                    frameAdapter.consume(frame).forEach { chunk ->
                        if (chunk.isNotEmpty()) {
                            messageBuilder.append(chunk)
                            request.eventListener.onMessage(chunk)
                        }
                    }
                }
        }.getOrElse {
            val responses = request.executor.execute(request.prompt, request.model, emptyList())
            val text = CompletionTextExtractor.extract(responses)
            if (text.isNotBlank()) {
                messageBuilder.append(text)
                request.eventListener.onMessage(text)
            }
        }
    }
}
