package ee.carlrobert.codegpt.mcp

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class McpToolArgumentsParserTest {

    @Test
    fun `parse should preserve explicit null values and normalize integral doubles`() {
        val result = McpToolArgumentsParser.parse("""{"path":null,"limit":10.0,"ratio":1.5}""")

        assertThat(result.error).isNull()
        assertThat(result.arguments).containsEntry("path", null)
        assertThat(result.arguments["limit"]).isEqualTo(10)
        assertThat(result.arguments["ratio"]).isEqualTo(1.5)
    }

    @Test
    fun `parse should return explicit error on invalid json`() {
        val result = McpToolArgumentsParser.parse("""{"path":""")

        assertThat(result.arguments).isEmpty()
        assertThat(result.error).isNotBlank()
    }
}

