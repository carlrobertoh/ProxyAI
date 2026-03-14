package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponses
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class StreamingPayloadNormalizerTest {

    @Test
    fun `should unwrap sse data payload`() {
        assertThat(normalizeSsePayload("data: {\"id\":\"chunk-1\"}"))
            .isEqualTo("{\"id\":\"chunk-1\"}")
    }

    @Test
    fun `should ignore done sentinel`() {
        assertThat(normalizeSsePayload("data: [DONE]")).isNull()
    }

    @Test
    fun `should unwrap multiline sse event frames`() {
        assertThat(
            normalizeSsePayload("event: response.created\ndata: {\"type\":\"response.created\"}\n")
        ).isEqualTo("{\"type\":\"response.created\"}")
    }

    @Test
    fun `should unwrap compact sse frames where event and data share a line`() {
        assertThat(
            normalizeSsePayload("event: response.created data: {\"type\":\"response.created\"}")
        ).isEqualTo("{\"type\":\"response.created\"}")
    }

    @Test
    fun `custom openai client should decode sse wrapped chat completion chunks`() {
        val state = CustomServiceChatCompletionSettingsState().apply {
            url = "https://example.com/v1/chat/completions"
            body["stream"] = true
        }
        val client = CustomOpenAILLMClient.fromSettingsState("test-key", state)
        val decodeMethod = client.javaClass.getDeclaredMethod("decodeStreamingResponse", String::class.java)
        decodeMethod.isAccessible = true

        val response = decodeMethod.invoke(
            client,
            "data: {\"choices\":[],\"created\":0,\"id\":\"chunk-1\",\"model\":\"test-model\"}"
        ) as CustomOpenAIChatCompletionStreamResponse

        assertThat(response.id).isEqualTo("chunk-1")
        assertThat(response.model).isEqualTo("test-model")
        assertThat(response.choices).isEmpty()
    }

    @Test
    fun `responses api streaming tool call should collapse into one named tool call`() {
        val state = CustomServiceChatCompletionSettingsState().apply {
            url = "https://example.com/v1/responses"
            body.clear()
            body["stream"] = true
            body["input"] = "\$OPENAI_MESSAGES"
        }
        val client = CustomOpenAILLMClient.fromSettingsState("test-key", state)
        val decodeMethod = client.javaClass.getDeclaredMethod("decodeStreamingResponse", String::class.java)
        decodeMethod.isAccessible = true
        val processMethod = client.javaClass.getDeclaredMethod("processStreamingResponse", Flow::class.java)
        processMethod.isAccessible = true

        val chunks = listOf(
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_diag","name":"Diagnostics","arguments":""},"output_index":0,"sequence_number":1}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"file_path\":\"/tmp/mainwindow.cpp\",\"filter\":\"all\"}","call_id":"call_diag","sequence_number":2}""",
            """{"type":"response.completed","response":{"id":"resp-tool","object":"response","created_at":1,"model":"test-model","output":[{"type":"function_call","id":"fc_1","call_id":"call_diag","name":"Diagnostics","arguments":"{\"file_path\":\"/tmp/mainwindow.cpp\",\"filter\":\"all\"}","status":"completed"}],"parallel_tool_calls":true,"status":"completed","text":{}},"sequence_number":3}"""
        ).map { payload ->
            decodeMethod.invoke(client, payload) as CustomOpenAIChatCompletionStreamResponse
        }

        val responses = runBlocking {
            @Suppress("UNCHECKED_CAST")
            val frames = processMethod.invoke(client, chunks.asFlow()) as Flow<StreamFrame>
            frames.toList().toMessageResponses()
        }
        val toolCall = responses.filterIsInstance<Message.Tool.Call>().single()

        assertThat(toolCall.id).isEqualTo("call_diag")
        assertThat(toolCall.tool).isEqualTo("Diagnostics")
        assertThat(toolCall.content).isEqualTo("""{"file_path":"/tmp/mainwindow.cpp","filter":"all"}""")
    }
}
