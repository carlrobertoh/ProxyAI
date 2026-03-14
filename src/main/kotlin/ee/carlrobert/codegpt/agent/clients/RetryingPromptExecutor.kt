package ee.carlrobert.codegpt.agent.clients

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ee.carlrobert.codegpt.agent.AgentEvents
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Wraps a [PromptExecutor] and retries the LLM call on network/timeouts.
 */
class RetryingPromptExecutor(
    private val delegate: PromptExecutor,
    private val retryPolicy: RetryPolicy,
    private val events: AgentEvents?
) : PromptExecutor {

    companion object {
        private val logger = KotlinLogging.logger { }
        private val RETRYABLE_HTTP_STATUS_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504)

        fun fromClient(
            client: LLMClient,
            retryPolicy: RetryPolicy,
            events: AgentEvents?
        ): PromptExecutor {
            return RetryingPromptExecutor(
                delegate = SingleLLMPromptExecutor(client),
                retryPolicy = retryPolicy,
                events = events
            )
        }

        internal fun isRetryableFailure(error: Throwable): Boolean {
            if (error is CancellationException && error !is TimeoutCancellationException) {
                return false
            }

            val causes = generateSequence(error) { it.cause }.take(10).toList()
            if (causes.any { it.isRetryableTimeout() }) {
                return true
            }

            val statusCode = causes
                .filterIsInstance<KoogHttpClientException>()
                .firstNotNullOfOrNull { it.statusCode }
            return statusCode in RETRYABLE_HTTP_STATUS_CODES
        }

        private fun Throwable.isRetryableTimeout(): Boolean {
            if (this is TimeoutCancellationException || this is SocketTimeoutException) {
                return true
            }

            return this is IOException && hasTimeoutMessage()
        }

        private fun Throwable.hasTimeoutMessage(): Boolean {
            val message = message ?: return false
            return message.contains("timed out", ignoreCase = true)
                    || message.contains("timeout", ignoreCase = true)
        }
    }

    private class RetryStreamingRequestException(cause: Throwable) :
        Exception("Retrying streaming request", cause)

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        var hasReceivedData: Boolean

        fun createStream(attemptNum: Int): Flow<StreamFrame> {
            hasReceivedData = false

            return delegate.executeStreaming(prompt, model, tools)
                .onEach { hasReceivedData = true }
                .catch { error ->
                    val retryable = isRetryableFailure(error)
                    val shouldRetry =
                        retryable && !hasReceivedData && attemptNum < retryPolicy.maxAttempts
                    logger.warn {
                        "Stream error: ${error.javaClass.simpleName}, " +
                                "hasReceivedData=$hasReceivedData, " +
                                "attempt=$attemptNum/${retryPolicy.maxAttempts}, " +
                                "retryable=$retryable, " +
                                "willRetry=$shouldRetry"
                    }

                    if (shouldRetry) {
                        val nextAttempt = attemptNum + 1
                        logger.warn {
                            "Retrying streaming request (attempt $nextAttempt/${retryPolicy.maxAttempts})"
                        }
                        events?.onRetry(nextAttempt, retryPolicy.maxAttempts)

                        val delayMs = if (attemptNum == 1) {
                            retryPolicy.initialDelay.inWholeMilliseconds
                        } else {
                            val exponential = (retryPolicy.initialDelay.inWholeMilliseconds *
                                    retryPolicy.backoffMultiplier.pow(attemptNum.toDouble())).toLong()
                            exponential.coerceAtMost(retryPolicy.maxDelay.inWholeMilliseconds)
                        }
                        val jitterMs = (delayMs * retryPolicy.jitterFactor).toLong()
                        val jitteredDelay =
                            delayMs + (if (jitterMs > 0) Random.nextInt(jitterMs.toInt()) else 0)

                        delay(jitteredDelay)
                        throw RetryStreamingRequestException(error)
                    } else {
                        logger.error { "Streaming failed: $error" }
                        throw error
                    }
                }
        }

        val retryLoop = flow {
            var currentAttempt = 1
            while (currentAttempt <= retryPolicy.maxAttempts) {
                try {
                    createStream(currentAttempt).collect { emit(it) }
                    break
                } catch (_: RetryStreamingRequestException) {
                    if (currentAttempt < retryPolicy.maxAttempts) {
                        currentAttempt++
                        continue
                    } else {
                        throw IllegalStateException("Retry attempt overflow in streaming executor")
                    }
                } catch (ce: CancellationException) {
                    throw ce
                }
            }
        }

        return retryLoop
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = delegate.models()

    data class RetryPolicy(
        val maxAttempts: Int,
        val initialDelay: Duration,
        val maxDelay: Duration,
        val backoffMultiplier: Double,
        val jitterFactor: Double,
    )

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): List<Message.Response> {
        var attempt = 1
        var delay = retryPolicy.initialDelay
        var lastError: Throwable? = null

        while (attempt <= retryPolicy.maxAttempts) {
            try {
                return delegate.execute(prompt, model, tools)
            } catch (t: Throwable) {
                lastError = t
                val retryable = isRetryableFailure(t)
                if (!retryable || attempt >= retryPolicy.maxAttempts) throw t

                events?.onRetry(attempt + 1, retryPolicy.maxAttempts)

                val jitter = (delay.inWholeMilliseconds * retryPolicy.jitterFactor).toLong()
                val jitteredMs =
                    delay.inWholeMilliseconds + (if (jitter > 0) (0..jitter).random() else 0)
                delay(jitteredMs)

                val nextMs = (delay.inWholeMilliseconds * retryPolicy.backoffMultiplier).toLong()
                delay = nextMs.milliseconds.coerceAtMost(retryPolicy.maxDelay)
                attempt++
            }
        }
        throw lastError ?: IllegalStateException("Retry loop ended without result")
    }

    override fun close() {
        (delegate as? AutoCloseable)?.close()
    }
}
