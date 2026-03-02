package ee.carlrobert.codegpt.agent.clients

import ai.koog.http.client.KoogHttpClientException
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

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
    fun `should not retry when no HTTP status is present`() {
        assertThat(RetryingPromptExecutor.isRetryableFailure(RuntimeException("connection reset"))).isFalse()
    }

    @Test
    fun `should not retry for non-network application errors`() {
        assertThat(RetryingPromptExecutor.isRetryableFailure(IllegalArgumentException("invalid prompt"))).isFalse()
    }
}
