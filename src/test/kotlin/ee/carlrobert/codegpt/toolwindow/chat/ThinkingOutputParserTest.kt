package ee.carlrobert.codegpt.toolwindow.chat

import ee.carlrobert.codegpt.util.ThinkingOutputParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ThinkingOutputParserTest {

    @Test
    fun `when not processing then return empty string`() {
        val parser = ThinkingOutputParser()
        assertThat(parser.processChunk("Some text")).isEqualTo("Some text")
        assertThat(parser.thoughtProcess).isEmpty()
    }

    @Test
    fun `when processing chunk but thinking not finished then return empty string`() {
        val parser = ThinkingOutputParser()
        assertThat(parser.processChunk("<think>starting")).isEmpty()
        assertThat(parser.thoughtProcess).isEqualTo("starting")

        val finalOutput = parser.processChunk(" some processing...")

        assertThat(finalOutput).isEmpty()
        assertThat(parser.thoughtProcess).isEqualTo("starting some processing...")
    }

    @Test
    fun `when thinking finished then return everything after the last closing think tag`() {
        val parser = ThinkingOutputParser()
        parser.processChunk("<think>the internal thought</think>\n\n")
        assertThat(parser.thoughtProcess).isEqualTo("the internal thought")

        val finalOutput = parser.processChunk("Here is the user response.")

        assertThat(finalOutput).isEqualTo("Here is the user response.")
        assertThat(parser.thoughtProcess).isEqualTo("the internal thought")
    }

    @Test
    fun `accumulate chunks and return response only after final chunk with closing tag`() {
        val parser = ThinkingOutputParser()
        assertThat(parser.processChunk("<think>")).isEmpty()
        assertThat(parser.thoughtProcess).isEqualTo("")
        assertThat(parser.processChunk("some internal processing")).isEmpty()
        assertThat(parser.thoughtProcess).isEqualTo("some internal processing")
        assertThat(parser.processChunk(" with even more details... ")).isEmpty()
        assertThat(parser.thoughtProcess).isEqualTo("some internal processing with even more details... ")

        val finalOutput = parser.processChunk("</think>\n\nThe final answer.")

        assertThat(finalOutput).isEqualTo("The final answer.")
        assertThat(parser.thoughtProcess).isEqualTo("some internal processing with even more details...")
    }
}