package ee.carlrobert.codegpt.agent.clients

import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
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
}
