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

internal object StreamingCompletionRunner : CompletionRunner {

    override fun run(request: CompletionRunnerRequest): CancellableRequest {
        val streamRequest = request as? CompletionRunnerRequest.Streaming
            ?: throw IllegalArgumentException("StreamingCompletionRunner can only run Streaming requests")
        return executeAsync(streamRequest)
    }

    private fun executeAsync(request: CompletionRunnerRequest.Streaming): CancellableRequest {
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
                when (request.mode) {
                    StreamingMode.STREAMING -> {
                        val frameAdapter = ReasoningFrameTextAdapter()
                        request.executor.executeStreaming(
                            request.prompt,
                            request.model,
                            request.tools
                        ).collect { frame ->
                            frameAdapter.consume(frame).forEach { chunk ->
                                if (chunk.isNotEmpty()) {
                                    messageBuilder.append(chunk)
                                    request.eventListener.onMessage(chunk)
                                }
                            }
                        }
                    }

                    StreamingMode.SINGLE_RESPONSE -> {
                        val responses = request.executor.execute(request.prompt, request.model, request.tools)
                        val text = CompletionTextExtractor.extract(responses)
                        if (text.isNotBlank()) {
                            messageBuilder.append(text)
                            request.eventListener.onMessage(text)
                        }
                    }
                }

                if (cancelled.get()) {
                    request.eventListener.onCancelled(StringBuilder(messageBuilder))
                    return@launch
                }

                request.eventListener.onComplete(StringBuilder(messageBuilder))
            } catch (_: CancellationException) {
                request.eventListener.onCancelled(request.cancellationResultBuilder(messageBuilder))
            } catch (exception: Throwable) {
                request.eventListener.onError(
                    CompletionError(exception.message ?: "Failed to complete request"),
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
}
