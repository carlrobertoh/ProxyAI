package ee.carlrobert.codegpt.agent

import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ee.carlrobert.codegpt.agent.tools.EditTool
import ee.carlrobert.codegpt.agent.tools.ReadTool
import ee.carlrobert.codegpt.agent.tools.WriteTool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ToolSpecsTest {

    @Test
    fun `decode args decodes standard object payload`() {
        val payload = JSONObject(mapOf("file_path" to JSONPrimitive("/tmp/fixture.txt")))

        val actual = ToolSpecs.decodeArgsOrNull("Read", payload)

        assertThat(actual).isEqualTo(ReadTool.Args(filePath = "/tmp/fixture.txt"))
    }

    @Test
    fun `decode args decodes standard string payload`() {
        val actual = ToolSpecs.decodeArgsOrNull("Read", "{\"file_path\":\"/tmp/fixture.txt\"}")

        assertThat(actual).isEqualTo(ReadTool.Args(filePath = "/tmp/fixture.txt"))
    }

    @Test
    fun `decode args unwraps string encoded object payload`() {
        val actual = ToolSpecs.decodeArgsOrNull("Read", "\"{\\\"file_path\\\":\\\"/tmp/fixture.txt\\\"}\"")

        assertThat(actual).isEqualTo(ReadTool.Args(filePath = "/tmp/fixture.txt"))
    }

    @Test
    fun `decode args decodes edit payload directly from koog json object`() {
        val payload = JSONObject(
            mapOf(
                "file_path" to JSONPrimitive("/tmp/fixture.txt"),
                "old_string" to JSONPrimitive("line 1\nval text = \"before\""),
                "new_string" to JSONPrimitive("line 1\nval text = \"after\""),
                "short_description" to JSONPrimitive("Update quoted text"),
                "replace_all" to JSONPrimitive(false),
            )
        )

        val actual = ToolSpecs.decodeArgsOrNull("Edit", payload)

        assertThat(actual).isEqualTo(
            EditTool.Args(
                filePath = "/tmp/fixture.txt",
                oldString = "line 1\nval text = \"before\"",
                newString = "line 1\nval text = \"after\"",
                shortDescription = "Update quoted text",
                replaceAll = false
            )
        )
    }

    @Test
    fun `decode result decodes standard object payload`() {
        val payload = JSONObject(
            mapOf(
                "filePath" to JSONPrimitive("/tmp/fixture.txt"),
                "bytesWritten" to JSONPrimitive(12),
                "isNewFile" to JSONPrimitive(true),
                "message" to JSONPrimitive("ok"),
                "type" to JSONPrimitive("ee.carlrobert.codegpt.agent.tools.WriteTool.Result.Success"),
            )
        )

        val actual = ToolSpecs.decodeResultOrNull("Write", payload)

        assertThat(actual).isEqualTo(
            WriteTool.Result.Success(
                filePath = "/tmp/fixture.txt",
                bytesWritten = 12,
                isNewFile = true,
                message = "ok",
            )
        )
    }

    @Test
    fun `decode result decodes standard string payload`() {
        val actual = ToolSpecs.decodeResultOrNull(
            "Write",
            "{\"type\":\"ee.carlrobert.codegpt.agent.tools.WriteTool.Result.Success\",\"filePath\":\"/tmp/fixture.txt\",\"bytesWritten\":12,\"isNewFile\":true,\"message\":\"ok\"}",
        )

        assertThat(actual).isEqualTo(
            WriteTool.Result.Success(
                filePath = "/tmp/fixture.txt",
                bytesWritten = 12,
                isNewFile = true,
                message = "ok",
            )
        )
    }

    @Test
    fun `decode result unwraps string encoded object payload`() {
        val actual = ToolSpecs.decodeResultOrNull(
            "Write",
            "\"{\\\"type\\\":\\\"ee.carlrobert.codegpt.agent.tools.WriteTool.Result.Success\\\",\\\"filePath\\\":\\\"/tmp/fixture.txt\\\",\\\"bytesWritten\\\":12,\\\"isNewFile\\\":true,\\\"message\\\":\\\"ok\\\"}\"",
        )

        assertThat(actual).isEqualTo(
            WriteTool.Result.Success(
                filePath = "/tmp/fixture.txt",
                bytesWritten = 12,
                isNewFile = true,
                message = "ok",
            )
        )
    }
}
