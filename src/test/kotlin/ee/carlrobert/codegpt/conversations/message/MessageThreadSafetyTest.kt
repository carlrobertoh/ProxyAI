package ee.carlrobert.codegpt.conversations.message

import ee.carlrobert.codegpt.completions.ChatToolCall
import ee.carlrobert.codegpt.completions.ChatToolFunction
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MessageThreadSafetyTest {

    @Test
    fun `tool call updates should remain consistent under parallel writes`() {
        val message = Message("prompt")
        val tasks = 200
        val done = CountDownLatch(tasks)
        val pool = Executors.newFixedThreadPool(8)

        repeat(tasks) { index ->
            pool.execute {
                val callId = "call-$index"
                message.addToolCall(
                    ChatToolCall(
                        id = callId,
                        function = ChatToolFunction(name = "tool-$index", arguments = "{}")
                    )
                )
                message.addToolCallResult(callId, "result-$index")
                done.countDown()
            }
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        pool.shutdownNow()

        assertThat(message.toolCalls).hasSize(tasks)
        assertThat(message.toolCallResults).hasSize(tasks)
    }
}
