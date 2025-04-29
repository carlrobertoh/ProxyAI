package ee.carlrobert.codegpt.toolwindow.chat

import ee.carlrobert.codegpt.toolwindow.chat.parser.StreamOutputParser
import ee.carlrobert.codegpt.toolwindow.chat.parser.StreamParseResponse
import ee.carlrobert.codegpt.toolwindow.chat.parser.StreamParseResponse.StreamResponseType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class StreamOutputParserTest {

    private lateinit var streamOutputParser: StreamOutputParser

    @Before
    fun setUp() {
        streamOutputParser = StreamOutputParser()
    }

    @Test
    fun testTextOnlyInput() {
        val input = "This is just plain text without any code blocks."

        val result = streamOutputParser.parse(input)

        assertThat(result).hasSize(1)
        assertThat(result[0].type).isEqualTo(StreamResponseType.TEXT)
        assertThat(result[0].content).isEqualTo(input)
        assertThat(result[0].language).isNull()
        assertThat(result[0].filePath).isNull()
    }

    @Test
    fun testMultipleCodeBlocksWithThinking() {
        val input = """
            <think>
            Here's some long thinking process
            </think>
            
            Here's some Java code:
            
            ```java
            public class Test {
                public static void main(String[] args) {
                    System.out.println("Hello");
                }
            }
            ```
            
            Here's some Python code:
            ```python:/path/to/my/file.py
            def hello():
                print("Hello")
            ```
            
            Here's a basic markdown:
            ```
            Some basic text
            ```
            
            End of `example`.
        """.trimIndent()

        val response = simulateStreamedInput(input)

        assertThat(response.flatten())
            .extracting({ it.type }, { it.content.trim() }, { it.language }, { it.filePath })
            .contains(
                Tuple.tuple(StreamResponseType.THINKING, "Here's some long thinking process", null, null),
                Tuple.tuple(StreamResponseType.TEXT, "Here's some Java code:", null, null),
                Tuple.tuple(StreamResponseType.CODE_HEADER, "", "java", null),
                Tuple.tuple(
                    StreamResponseType.CODE_CONTENT, "public class Test {\n" +
                            "    public static void main(String[] args) {\n" +
                            "        System.out.println(\"Hello\");\n" +
                            "    }\n" +
                            "}", "java", null
                ),
                Tuple.tuple(StreamResponseType.TEXT, "Here's some Python code:", null, null),
                Tuple.tuple(StreamResponseType.CODE_HEADER, "", "python", "/path/to/my/file.py"),
                Tuple.tuple(
                    StreamResponseType.CODE_CONTENT,
                    "def hello():\n    print(\"Hello\")",
                    "python",
                    "/path/to/my/file.py"
                ),
                Tuple.tuple(StreamResponseType.TEXT, "Here's a basic markdown:", null, null),
                Tuple.tuple(StreamResponseType.CODE_HEADER, "", "", null),
                Tuple.tuple(StreamResponseType.CODE_CONTENT, "Some basic text", "", null),
                Tuple.tuple(StreamResponseType.TEXT, "End of `example`.", null, null)
            )
    }

    @Test
    fun testMultipleCodeBlocksWithoutThinking() {
        val input = """
            Here's some Java code:
            
            ```java
            public class Test {
                public static void main(String[] args) {
                    System.out.println("Hello");
                }
            }
            ```
            
            Here's some Python code:
            ```python:/path/to/my/file.py
            def hello():
                print("Hello")
            ```
            
            Here's a basic markdown:
            ```
            Some basic text
            ```
            
            End of `example`.
        """.trimIndent()

        val response = simulateStreamedInput(input)

        assertThat(response.flatten())
            .extracting({ it.type }, { it.content.trim() }, { it.language }, { it.filePath })
            .contains(
                Tuple.tuple(StreamResponseType.TEXT, "Here's some Java code:", null, null),
                Tuple.tuple(StreamResponseType.CODE_HEADER, "", "java", null),
                Tuple.tuple(
                    StreamResponseType.CODE_CONTENT, "public class Test {\n" +
                            "    public static void main(String[] args) {\n" +
                            "        System.out.println(\"Hello\");\n" +
                            "    }\n" +
                            "}", "java", null
                ),
                Tuple.tuple(StreamResponseType.TEXT, "Here's some Python code:", null, null),
                Tuple.tuple(StreamResponseType.CODE_HEADER, "", "python", "/path/to/my/file.py"),
                Tuple.tuple(
                    StreamResponseType.CODE_CONTENT,
                    "def hello():\n    print(\"Hello\")",
                    "python",
                    "/path/to/my/file.py"
                ),
                Tuple.tuple(StreamResponseType.TEXT, "Here's a basic markdown:", null, null),
                Tuple.tuple(StreamResponseType.CODE_HEADER, "", "", null),
                Tuple.tuple(StreamResponseType.CODE_CONTENT, "Some basic text", "", null),
                Tuple.tuple(StreamResponseType.TEXT, "End of `example`.", null, null)
            )
    }

    /**
     * Simulates streaming input by breaking the input string into random chunks
     * and feeding them to the StreamParser.
     *
     * @param input The complete input string
     * @return List of responses from each parse call
     */
    private fun simulateStreamedInput(input: String): List<List<StreamParseResponse>> {
        streamOutputParser.clear()
        val responses = mutableListOf<List<StreamParseResponse>>()
        var remainingInput = input

        while (remainingInput.isNotEmpty()) {
            // Take a random chunk size between 1 and the remaining length
            val chunkSize = Random.nextInt(1, minOf(remainingInput.length + 1, 10))
            val chunk = remainingInput.substring(0, chunkSize)
            remainingInput = remainingInput.substring(chunkSize)

            val response = streamOutputParser.parse(chunk)
            responses.add(response)
        }

        return responses
    }
}
