package ee.carlrobert.codegpt.toolwindow.chat.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CompleteMessageParserTest {

    @Test
    fun `test parses mermaid block with long fence and header metadata`() {
        val parser = CompleteMessageParser()
        val input = """
            Intro text
            ````mermaid title="Architecture"
            flowchart LR
              A --> B
            ````
            Outro text
        """.trimIndent()

        val segments = parser.parse(input)
        val codeHeaders = segments.filterIsInstance<CodeHeader>()
        val codeSegments = segments.filterIsInstance<Code>()
        val codeEnds = segments.filterIsInstance<CodeEnd>()

        assertThat(codeHeaders).hasSize(1)
        assertThat(codeHeaders[0].language).isEqualTo("mermaid")
        assertThat(codeSegments).hasSize(1)
        assertThat(codeSegments[0].content).contains("flowchart LR")
        assertThat(codeSegments[0].content).doesNotContain("````")
        assertThat(codeEnds).hasSize(1)
    }

    @Test
    fun `test preserves language and file path for classic header`() {
        val parser = CompleteMessageParser()
        val input = """
            ```kotlin:src/Main.kt
            fun main() = Unit
            ```
        """.trimIndent()

        val segments = parser.parse(input)
        val codeHeader = segments.filterIsInstance<CodeHeader>().single()
        val code = segments.filterIsInstance<Code>().single()

        assertThat(codeHeader.language).isEqualTo("kotlin")
        assertThat(codeHeader.filePath).isEqualTo("src/Main.kt")
        assertThat(code.language).isEqualTo("kotlin")
        assertThat(code.filePath).isEqualTo("src/Main.kt")
    }

    @Test
    fun `test recovers inline search marker from code header`() {
        val parser = CompleteMessageParser()
        val input = """
            ```kotlin:RetryingPromptExecutorRetryabilityTest.kt<<<<<<< SEARCH @Test fun oldRetryTest() {
                assertThat(false).isTrue()
            }
            =======
            @Test fun newRetryTest() {
                assertThat(true).isTrue()
            }
            >>>>>>> REPLACE
            ```
        """.trimIndent()

        val segments = parser.parse(input)
        val codeHeader = segments.filterIsInstance<CodeHeader>().single()
        val searchReplace = segments.filterIsInstance<SearchReplace>().single()

        assertThat(codeHeader.language).isEqualTo("kotlin")
        assertThat(codeHeader.filePath).isEqualTo("RetryingPromptExecutorRetryabilityTest.kt")
        assertThat(searchReplace.search).contains("@Test fun oldRetryTest")
        assertThat(searchReplace.search).doesNotContain("<<<<<<<")
        assertThat(searchReplace.replace).contains("@Test fun newRetryTest")
    }
}
