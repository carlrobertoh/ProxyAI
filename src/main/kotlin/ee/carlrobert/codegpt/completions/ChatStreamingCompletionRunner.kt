package ee.carlrobert.codegpt.completions

import ee.carlrobert.codegpt.util.ReasoningFrameTextAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal object ChatStreamingCompletionRunner : CompletionRunner {

    override fun run(request: CompletionRunnerRequest): CancellableRequest {
        val chatRequest = request as? CompletionRunnerRequest.Chat
            ?: throw IllegalArgumentException("ChatStreamingCompletionRunner can only run Chat requests")
        return executeAsync(chatRequest)
    }

    private fun executeAsync(request: CompletionRunnerRequest.Chat): CancellableRequest {
        val asyncRequest = AsyncRequestContext()
        val scope = asyncRequest.scope

        request.eventListener.onOpen()
        val job = scope.launch {
            val messageBuilder = StringBuilder()
            try {
                streamOrFallback(request, messageBuilder)

                if (asyncRequest.isCancelled()) {
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
        asyncRequest.attach(job)

        return asyncRequest.cancellableRequest
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
                        emit(chunk, request, messageBuilder)
                    }
                }
        }.getOrElse {
            val responses = request.executor.execute(request.prompt, request.model, emptyList())
            val text = CompletionTextExtractor.extract(responses)
            emit(text, request, messageBuilder)
        }
    }

    private fun emit(
        text: String,
        request: CompletionRunnerRequest.Chat,
        messageBuilder: StringBuilder
    ) {
        messageBuilder.append(text)
        request.eventListener.onMessage(text)
    }
}
