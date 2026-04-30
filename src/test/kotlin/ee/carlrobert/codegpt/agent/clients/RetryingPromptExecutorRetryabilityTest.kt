package ee.carlrobert.codegpt.agent.clients

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ee.carlrobert.codegpt.agent.AgentEvents
import ee.carlrobert.codegpt.agent.MessageWithContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

class RetryingPromptExecutorRetryabilityTest {

    @Test
    fun `should retry for transient HTTP status codes`() {
        val tooManyRequests = KoogHttpClientException("rate limited", 429, null, null, null)
        val unavailable = KoogHttpClientException("service unavailable", 503, null, null, null)

        assertThat(RetryingPromptExecutor.isRetryableFailure(tooManyRequests)).isTrue()
        assertThat(RetryingPromptExecutor.isRetryableFailure(unavailable)).isTrue()
    }

    @Test
    fun `should not retry for non-transient HTTP status codes`() {
        val badRequest = KoogHttpClientException("bad request", 400, null, null, null)
        val unauthorized = KoogHttpClientException("unauthorized", 401, null, null, null)

        assertThat(RetryingPromptExecutor.isRetryableFailure(badRequest)).isFalse()
        assertThat(RetryingPromptExecutor.isRetryableFailure(unauthorized)).isFalse()
    }

    @Test
    fun `should retry when transient status is in nested cause chain`() {
        val nested = RuntimeException(
            "outer",
            KoogHttpClientException("service unavailable", 503, null, null, null)
        )
        assertThat(RetryingPromptExecutor.isRetryableFailure(nested)).isTrue()
    }

    @Test
    fun `should retry when transport timeout is in nested cause chain`() {
        val timeout = IOException("Operation timed out")
        val nested = KoogHttpClientException(
            "Error from client: InceptionAILLMClient\nMessage: Operation timed out",
            null,
            null,
            null,
            RuntimeException("sse wrapper", timeout)
        )

        assertThat(RetryingPromptExecutor.isRetryableFailure(nested)).isTrue()
    }

    @Test
    fun `should not retry when no HTTP status is present`() {
        assertThat(RetryingPromptExecutor.isRetryableFailure(RuntimeException("connection reset"))).isFalse()
    }

    @Test
    fun `should not retry for non-network application errors`() {
        assertThat(RetryingPromptExecutor.isRetryableFailure(IllegalArgumentException("invalid prompt"))).isFalse()
    }

    @Test
    fun `should notify when streaming retry succeeds after receiving data`() {
        runBlocking {
            val events = RecordingAgentEvents()
            val executor = retryingExecutor(FailingPromptExecutor(failuresBeforeSuccess = 2), events)

            val frames = executor.executeStreaming(testPrompt(), testModel(), emptyList()).toList()

            assertThat(frames).containsExactly(
                StreamFrame.TextDelta("ok"),
                StreamFrame.End()
            )
            assertThat(events.events).containsExactly(
                "retry:2/5",
                "retry:3/5",
                "retrySucceeded"
            )
        }
    }

    @Test
    fun `should notify when non-streaming retry succeeds`() {
        runBlocking {
            val events = RecordingAgentEvents()
            val executor = retryingExecutor(FailingPromptExecutor(failuresBeforeSuccess = 1), events)

            val responses = executor.execute(testPrompt(), testModel(), emptyList())

            assertThat(responses.map { it.content }).containsExactly("ok")
            assertThat(events.events).containsExactly(
                "retry:2/5",
                "retrySucceeded"
            )
        }
    }

    private fun retryingExecutor(
        delegate: PromptExecutor,
        events: AgentEvents
    ) = RetryingPromptExecutor(
        delegate = delegate,
        retryPolicy = RetryingPromptExecutor.RetryPolicy(
            maxAttempts = 5,
            initialDelay = 0.milliseconds,
            maxDelay = 0.milliseconds,
            backoffMultiplier = 1.0,
            jitterFactor = 0.0
        ),
        events = events
    )

    private fun testPrompt() = prompt("retry-test") {
        user("Hello")
    }

    private fun testModel() = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = emptyList()
    )

    private class FailingPromptExecutor(
        private val failuresBeforeSuccess: Int
    ) : PromptExecutor() {

        private var streamingCalls = 0
        private var executeCalls = 0

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = flow {
            streamingCalls++
            if (streamingCalls <= failuresBeforeSuccess) {
                throw IOException("Operation timed out")
            }
            emit(StreamFrame.TextDelta("ok"))
            emit(StreamFrame.End())
        }

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): List<Message.Response> {
            executeCalls++
            if (executeCalls <= failuresBeforeSuccess) {
                throw IOException("Operation timed out")
            }
            return listOf(Message.Assistant("ok", ResponseMetaInfo.Empty))
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
            throw UnsupportedOperationException("Not used")
        }

        override fun close() = Unit
    }

    private class RecordingAgentEvents : AgentEvents {

        val events = mutableListOf<String>()

        override fun onRetry(attempt: Int, maxAttempts: Int) {
            events.add("retry:$attempt/$maxAttempts")
        }

        override fun onRetrySucceeded() {
            events.add("retrySucceeded")
        }

        override fun onQueuedMessagesResolved(message: MessageWithContext?) = Unit
    }
}
