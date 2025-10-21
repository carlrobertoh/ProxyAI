package ee.carlrobert.codegpt.toolwindow.chat.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import kotlin.random.Random

class SseMessageParserStreamTest {

    /**
     * Simulates streaming by breaking input into random chunks
     */
    private fun simulateStreaming(
        parser: SseMessageParser,
        input: String,
        minChunkSize: Int = 1,
        maxChunkSize: Int = 10,
        seed: Long? = null
    ): List<Segment> {
        val random = seed?.let { Random(it) } ?: Random
        val allSegments = mutableListOf<Segment>()
        var position = 0

        while (position < input.length) {
            val chunkSize = random.nextInt(minChunkSize, maxChunkSize + 1)
                .coerceAtMost(input.length - position)
            val chunk = input.substring(position, position + chunkSize)

            val segments = parser.parse(chunk)
            allSegments.addAll(segments)

            position += chunkSize

            // Simulate network delay
            Thread.sleep(random.nextLong(5, 20))
        }

        return allSegments
    }

    @Test
    fun shouldHandleStreamedCodeBlock() {
        val parser = SseMessageParser()
        val input = """
            Here is some code:
            ```kotlin:MyFile.kt
            fun main() {
                println("Hello, World!")
            }
            ```
            Done!
        """.trimIndent()

        val segments = simulateStreaming(parser, input, seed = 42)

        val segmentTypes = segments.map { it::class.simpleName }
        assertThat(segmentTypes).contains("Text", "CodeHeader", "Code", "CodeEnd")
        val codeSegments = segments.filterIsInstance<Code>()
        assertThat(codeSegments).isNotEmpty
        assertThat(codeSegments.last().content).contains("println(\"Hello, World!\")")
    }

    @Test
    fun shouldHandleStreamedSearchReplace() {
        val parser = SseMessageParser()
        val input = """
            ```kotlin:MyFile.kt
            <<<<<<< SEARCH
            fun oldFunction() {
                return "old"
            }
            =======
            fun newFunction() {
                return "new"
            }
            >>>>>>> REPLACE
            ```
        """.trimIndent()

        val segments = simulateStreaming(parser, input, minChunkSize = 3, maxChunkSize = 15, seed = 123)

        val searchReplaceSegments = segments.filterIsInstance<SearchReplace>()
        assertThat(searchReplaceSegments).hasSize(1)
        val sr = searchReplaceSegments[0]
        assertThat(sr.search).contains("oldFunction")
        assertThat(sr.replace).contains("newFunction")
        assertThat(sr.language).isEqualTo("kotlin")
        assertThat(sr.filePath).isEqualTo("MyFile.kt")
    }

    @Test
    fun shouldHandleSearchReplaceWithInvalidEnding() {
        val parser = SseMessageParser()
        val input = """
            Here's some text.
            
            ```kotlin:MyFile.kt
            <<<<<<< SEARCH
            fun oldFunction() {
                return "old"
            }
            =======
            fun newFunction() {
                return "new"
            }
            ```
            
            Here's some other text.
        """.trimIndent()

        val segments = simulateStreaming(parser, input, minChunkSize = 3, maxChunkSize = 15, seed = 123)

        val searchReplaceSegments = segments.filterIsInstance<SearchReplace>()
        assertThat(searchReplaceSegments).isEmpty()
        val codeEndSegments = segments.filterIsInstance<CodeEnd>()
        assertThat(codeEndSegments).hasSize(1)
        val textSegments = segments.filterIsInstance<Text>()
        assertThat(textSegments.any { it.content.contains("Here's some other text") }).isTrue
        val replaceWaitingSegments = segments.filterIsInstance<ReplaceWaiting>()
        assertThat(replaceWaitingSegments.size).isLessThan(10)
    }

    @Test
    fun shouldHandleStreamedThinkingBlock() {
        val parser = SseMessageParser()
        val input = """
            Let me analyze this...
            <think>
            First, I need to understand the requirements.
            Then, I'll design a solution.
            Finally, I'll implement it.
            </think>
            Here's my solution:
        """.trimIndent()

        val segments = simulateStreaming(parser, input, minChunkSize = 2, maxChunkSize = 8, seed = 456)

        val thinkingSegments = segments.filterIsInstance<Thinking>()
        assertThat(thinkingSegments).isNotEmpty
        val finalThinking = thinkingSegments.last()
        assertThat(finalThinking.content).contains("understand the requirements")
        assertThat(finalThinking.content).contains("design a solution")
        assertThat(finalThinking.content).contains("implement it")
    }

    @Test
    fun shouldHandleMultipleCodeBlocksStreamed() {
        val parser = SseMessageParser()
        val input = """
            First:
            ```java
            System.out.println("1");
            ```
            Second:
            ```python
            print("2")
            ```
            Third:
            ```javascript
            console.log("3");
            ```
        """.trimIndent()

        val segments = simulateStreaming(parser, input, minChunkSize = 5, maxChunkSize = 20, seed = 789)

        val codeHeaders = segments.filterIsInstance<CodeHeader>()
        assertThat(codeHeaders).hasSize(3)
        assertThat(codeHeaders.map { it.language }).containsExactly("java", "python", "javascript")
        val codeSegments = segments.filterIsInstance<Code>()
        assertThat(codeSegments.any { it.content.contains("System.out.println") }).isTrue
        assertThat(codeSegments.any { it.content.contains("print(\"2\")") }).isTrue
        assertThat(codeSegments.any { it.content.contains("console.log") }).isTrue
    }

    @Test
    fun shouldHandleMixedContentStreamed() {
        val parser = SseMessageParser()
        val input = """
            Starting analysis...
            <think>
            Processing request...
            </think>
            Here's the code:
            ```kotlin:Solution.kt
            <<<<<<< SEARCH
            val old = 1
            =======
            val new = 2
            >>>>>>> REPLACE
            ```
            And a simple block:
            ```python
            print("done")
            ```
        """.trimIndent()

        val segments = simulateStreaming(parser, input, minChunkSize = 3, maxChunkSize = 12, seed = 999)

        val segmentTypeSet = segments.map { it::class.simpleName }.toSet()
        assertThat(segmentTypeSet).contains(
            "Text", "Thinking", "CodeHeader", "SearchWaiting",
            "ReplaceWaiting", "SearchReplace", "Code", "CodeEnd"
        )
    }

    @Test
    fun shouldHandleRandomChunkingConsistently() {
        val input = """
            ```kotlin:Test.kt
            class Test {
                fun method() {
                    println("Hello")
                }
            }
            ```
        """.trimIndent()

        repeat(5) { iteration ->
            val parser = SseMessageParser()
            val segments = simulateStreaming(parser, input, seed = iteration.toLong())

            val codeSegments = segments.filterIsInstance<Code>()
            assertThat(codeSegments).isNotEmpty
            val finalCode = codeSegments.last().content
            assertThat(finalCode).contains("class Test")
            assertThat(finalCode).contains("println(\"Hello\")")
        }
    }

    @Test
    fun shouldHandleIndentedCodeBlockWithInnerBackticks() {
        val parser = SseMessageParser()
        val input = """
            Here is a code block:
              ```kotlin
              fun main() {
                  val text = ""${'"'}
                      ```
                      This should not end the block.
                      ```
                  ""${'"'}
                  println(text)
              }
              ```
            Done.
        """.trimIndent()

        val segments = simulateStreaming(parser, input, seed = 42)

        val codeSegments = segments.filterIsInstance<Code>()
        assertThat(codeSegments).isNotEmpty

        val finalCodeContent = codeSegments.last().content
        assertThat(finalCodeContent).contains("This should not end the block.")
        assertThat(finalCodeContent).contains("println(text)")

        val textSegments = segments.filterIsInstance<Text>()
        assertThat(textSegments.any { it.content.contains("Done.") }).isTrue

        val codeEndSegments = segments.filterIsInstance<CodeEnd>()
        assertThat(codeEndSegments).hasSize(1)
    }
}
