package ee.carlrobert.codegpt.util

import ai.koog.prompt.streaming.StreamFrame
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ReasoningFrameTextAdapterTest {

    @Test
    fun `should not duplicate reasoning complete when deltas were already emitted`() {
        val adapter = ReasoningFrameTextAdapter()

        assertThat(adapter.consume(StreamFrame.ReasoningDelta(text = "step 1", summary = null, index = 0)))
            .containsExactly("<think>", "step 1")
        assertThat(adapter.consume(StreamFrame.ReasoningDelta(text = " step 2", summary = null, index = 0)))
            .containsExactly(" step 2")

        assertThat(
            adapter.consume(
                StreamFrame.ReasoningComplete(
                    id = "1",
                    text = listOf("step 1 step 2"),
                    summary = emptyList(),
                    encrypted = null,
                    index = 0
                )
            )
        ).containsExactly("</think>")
    }

    @Test
    fun `should emit full reasoning complete when there were no deltas`() {
        val adapter = ReasoningFrameTextAdapter()

        assertThat(
            adapter.consume(
                StreamFrame.ReasoningComplete(
                    id = "1",
                    text = listOf("full reasoning"),
                    summary = emptyList(),
                    encrypted = null,
                    index = 1
                )
            )
        ).containsExactly("<think>", "full reasoning", "</think>")
    }

    @Test
    fun `should deduplicate for null reasoning index`() {
        val adapter = ReasoningFrameTextAdapter()

        assertThat(adapter.consume(StreamFrame.ReasoningDelta(text = "delta", summary = null, index = null)))
            .containsExactly("<think>", "delta")

        assertThat(
            adapter.consume(
                StreamFrame.ReasoningComplete(
                    id = "1",
                    text = listOf("delta"),
                    summary = emptyList(),
                    encrypted = null,
                    index = null
                )
            )
        ).containsExactly("</think>")
    }
}
