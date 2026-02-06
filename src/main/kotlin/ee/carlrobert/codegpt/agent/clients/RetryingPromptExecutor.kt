package ee.carlrobert.codegpt.agent.clients

import ai.koog.agents.core.tools.ToolDescriptor
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerializationException
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
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        var attempt = 1
        var refinedPrompt = prompt
        var hasReceivedData: Boolean

        fun createStream(attemptNum: Int): Flow<StreamFrame> {
            hasReceivedData = false

            return delegate.executeStreaming(refinedPrompt, model, tools)
                .onEach { hasReceivedData = true }
                .catch { error ->
                    val shouldRetry = !hasReceivedData && attemptNum < retryPolicy.maxAttempts
                    logger.warn {
                        "Stream error: ${error.javaClass.simpleName}, " +
                                "hasReceivedData=$hasReceivedData, " +
                                "attempt=$attemptNum/${retryPolicy.maxAttempts}, " +
                                "willRetry=$shouldRetry"
                    }

                    if (shouldRetry) {
                        attempt++
                        logger.warn { "Retrying streaming request (attempt $attempt/${retryPolicy.maxAttempts})" }
                        events?.onRetry(
                            attempt,
                            retryPolicy.maxAttempts,
                            error.javaClass.simpleName
                        )

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
                        throw CancellationException("Retrying streaming request", error)
                    } else {
                        logger.error { "Streaming failed: $error" }
                        throw error
                    }
                }
        }

        val retryLoop = flow {
            var currentAttempt = attempt
            while (currentAttempt <= retryPolicy.maxAttempts) {
                try {
                    createStream(currentAttempt).collect { emit(it) }
                    break
                } catch (ce: CancellationException) {
                    if (ce.message == "Retrying streaming request" && currentAttempt < retryPolicy.maxAttempts) {
                        currentAttempt++
                        continue
                    } else {
                        throw ce
                    }
                } catch (_: SerializationException) {
                    refinedPrompt = prompt.copy(messages = prompt.messages.dropLast(1))
                    currentAttempt++
                    continue
                }
            }
        }

        return retryLoop
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override suspend fun models(): List<String> = delegate.models()

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
                if (attempt > 1) {
                    events?.onRetry(
                        attempt,
                        retryPolicy.maxAttempts,
                        lastError?.javaClass?.simpleName
                    )
                }
                return delegate.execute(prompt, model, tools)
            } catch (t: Throwable) {
                lastError = t
                if (attempt >= retryPolicy.maxAttempts) throw t

                events?.onRetry(attempt + 1, retryPolicy.maxAttempts, t.javaClass.simpleName)

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
