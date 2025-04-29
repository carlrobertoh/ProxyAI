package ee.carlrobert.codegpt.toolwindow.chat

import ee.carlrobert.codegpt.toolwindow.chat.parser.CompleteOutputParser
import ee.carlrobert.codegpt.toolwindow.chat.parser.StreamParseResponse
import ee.carlrobert.codegpt.toolwindow.chat.parser.StreamParseResponse.StreamResponseType
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CompleteOutputParserTest {

    @Test
    fun `parse should return empty list for empty input`() {
        val input = ""

        val result = CompleteOutputParser().parse(input)

        assertThat(result).isEmpty()
    }

    @Test
    fun `parse should return single text element for text only input`() {
        val input = "This is just plain text without any code blocks."

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.TEXT, content = input)
        )
    }

    @Test
    fun `parse should handle single code block with language`() {
        val language = "java"
        val code = """
            public class Test {
                public static void main(String[] args) {
                    System.out.println("Hello");
                }
            }
        """.trimIndent()
        val input = "Here's some Java code:\n```java\n$code\n```\nEnd of example."

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.TEXT, content = "Here's some Java code:\n"),
            expectedResponse(StreamResponseType.CODE_HEADER, language = language),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$code\n",
                language = language
            ),
            expectedResponse(
                StreamResponseType.CODE_END,
                language = language
            ),
            expectedResponse(StreamResponseType.TEXT, content = "\nEnd of example.")
        )
    }

    @Test
    fun `parse should handle code block with file path`() {
        val language = "python"
        val filePath = "src/main.py"
        val code = """
            def hello():
                print('Hello, world!')
        """.trimIndent()
        val input = "```python:src/main.py\n$code\n```"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(
                StreamResponseType.CODE_HEADER,
                language = language,
                filePath = filePath
            ),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$code\n",
                language = language,
                filePath = filePath
            ),
            expectedResponse(StreamResponseType.CODE_END, language = language, filePath = filePath)
        )
    }

    @Test
    fun `parse should handle multiple code blocks`() {
        val javaCode = "System.out.println();"
        val pythonCode = "print('hello')"
        val input =
            "First block:\n```java\n$javaCode\n```\nSecond block:\n```python\n$pythonCode\n```"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.TEXT, content = "First block:\n"),
            expectedResponse(StreamResponseType.CODE_HEADER, language = "java"),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$javaCode\n",
                language = "java"
            ),
            expectedResponse(StreamResponseType.CODE_END, language = "java"),
            expectedResponse(StreamResponseType.TEXT, content = "\nSecond block:\n"),
            expectedResponse(StreamResponseType.CODE_HEADER, language = "python"),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$pythonCode\n",
                language = "python"
            ),
            expectedResponse(StreamResponseType.CODE_END, language = "python")
        )
    }

    @Test
    fun `parse should handle code block without language`() {
        val code = "const x = 10;"
        val input = "Code without language specification:\n```\n$code\n```"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(
                StreamResponseType.TEXT,
                content = "Code without language specification:\n"
            ),
            expectedResponse(
                StreamResponseType.CODE_HEADER,
                language = ""
            ),
            expectedResponse(StreamResponseType.CODE_CONTENT, content = "$code\n", language = ""),
            expectedResponse(StreamResponseType.CODE_END, language = "")
        )
    }

    @Test
    fun `parse should handle windows line endings`() {
        val code = "System.out.println();"
        val input = "Windows line endings:\r\n```java\r\n$code\r\n```"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.TEXT, content = "Windows line endings:\n"),
            expectedResponse(StreamResponseType.CODE_HEADER, language = "java"),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$code\n",
                language = "java"
            ),
            expectedResponse(StreamResponseType.CODE_END, language = "java")
        )
    }

    @Test
    fun `parse should handle nested backticks within code block`() {
        val code = "console.log(`Template literal with backticks`);"
        val input = "Nested backticks example:\n```javascript\n$code\n```"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.TEXT, content = "Nested backticks example:\n"),
            expectedResponse(StreamResponseType.CODE_HEADER, language = "javascript"),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$code\n",
                language = "javascript"
            ),
            expectedResponse(StreamResponseType.CODE_END, language = "javascript")
        )
    }

    @Test
    fun `parse should handle special characters in language specifier`() {
        val code = "std::cout << \"Hello\";"
        val input = "Special language name:\n```c++\n$code\n```"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.TEXT, content = "Special language name:\n"),
            expectedResponse(StreamResponseType.CODE_HEADER, language = "c++"),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$code\n",
                language = "c++"
            ),
            expectedResponse(StreamResponseType.CODE_END, language = "c++")
        )
    }

    @Test
    fun `parse should treat incomplete code block as text`() {
        val input = "Incomplete code block:\n```java\nSystem.out.println();"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.TEXT, content = input)
        )
    }

    @Test
    fun `parse should handle adjacent code blocks`() {
        val javaCode = "int x = 1;"
        val pythonCode = "print(2)"
        val input = "```java\n$javaCode\n``````python\n$pythonCode\n```"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.CODE_HEADER, language = "java"),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$javaCode\n",
                language = "java"
            ),
            expectedResponse(StreamResponseType.CODE_END, language = "java"),
            expectedResponse(StreamResponseType.CODE_HEADER, language = "python"),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$pythonCode\n",
                language = "python"
            ),
            expectedResponse(StreamResponseType.CODE_END, language = "python")
        )
    }

    @Test
    fun `parse should handle code block with empty content`() {
        val input = "Empty code block:\n```java\n\n```"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.TEXT, content = "Empty code block:\n"),
            expectedResponse(StreamResponseType.CODE_HEADER, language = "java"),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "\n",
                language = "java"
            ),
            expectedResponse(StreamResponseType.CODE_END, language = "java")
        )
    }

    @Test
    fun `parse should handle hyphen in language specifier`() {
        val code = "NSLog(@\"Hello\");"
        val input = "Language with hyphen:\n```objective-c\n$code\n```"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.TEXT, content = "Language with hyphen:\n"),
            expectedResponse(StreamResponseType.CODE_HEADER, language = "objective-c"),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$code\n",
                language = "objective-c"
            ),
            expectedResponse(StreamResponseType.CODE_END, language = "objective-c")
        )
    }

    @Test
    fun `parse should handle plus in language specifier`() {
        val code = "std::cout << \"Hello\";"
        val input = "Language with plus:\n```c++\n$code\n```"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.TEXT, content = "Language with plus:\n"),
            expectedResponse(StreamResponseType.CODE_HEADER, language = "c++"),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$code\n",
                language = "c++"
            ),
            expectedResponse(StreamResponseType.CODE_END, language = "c++")
        )
    }

    @Test
    fun `parse should handle underscore in language specifier`() {
        val code = "print(\"Hello\");"
        val input = "Language with underscore:\n```some_lang\n$code\n```"

        val result = CompleteOutputParser().parse(input)

        assertThat(result).containsExactly(
            expectedResponse(StreamResponseType.TEXT, content = "Language with underscore:\n"),
            expectedResponse(StreamResponseType.CODE_HEADER, language = "some_lang"),
            expectedResponse(
                StreamResponseType.CODE_CONTENT,
                content = "$code\n",
                language = "some_lang"
            ),
            expectedResponse(StreamResponseType.CODE_END, language = "some_lang")
        )
    }

    private fun expectedResponse(
        type: StreamResponseType,
        content: String? = null,
        language: String? = null,
        filePath: String? = null
    ): StreamParseResponse {
        return when (type) {
            StreamResponseType.TEXT -> StreamParseResponse.Text(content ?: "")
            StreamResponseType.THINKING -> StreamParseResponse.Thinking(content ?: "")
            StreamResponseType.CODE_HEADER -> StreamParseResponse.CodeHeader(
                language ?: "",
                filePath
            )

            StreamResponseType.CODE_CONTENT -> StreamParseResponse.CodeContent(
                content ?: "",
                language ?: "",
                filePath
            )

            StreamResponseType.CODE_END -> StreamParseResponse.CodeEnd(language ?: "", filePath)
        }
    }
}