package ee.carlrobert.codegpt.completions.factory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionMessage
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionStandardMessage
import okhttp3.Request
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.Test
import java.util.function.Function

class CustomOpenAIRequestFactoryTest {

    @Test
    fun `should transform nested placeholder values for chat request body`() {
        val settings = CustomServiceChatCompletionSettingsState().apply {
            url = "https://example.com/v1/chat/completions"
            headers = mutableMapOf("Authorization" to $$"Bearer $CUSTOM_SERVICE_API_KEY")
            body = mutableMapOf(
                "model" to "gpt-test",
                "payload" to mapOf(
                    "prompt_alias" to $$"$PROMPT",
                    "messages_alias" to $$"$OPENAI_MESSAGES",
                    "items" to listOf(
                        mapOf("kind" to "prompt", "value" to $$"$PROMPT"),
                        mapOf("kind" to "messages", "value" to $$"$OPENAI_MESSAGES")
                    )
                )
            )
        }
        val messages = listOf(
            OpenAIChatCompletionStandardMessage("system", "System instructions"),
            OpenAIChatCompletionStandardMessage("user", "Write a test"),
        )

        val request = CustomOpenAIRequestFactory.buildCustomOpenAIChatCompletionRequest(
            settings = settings,
            messages = messages,
            streamRequest = true,
            credential = "secret-key",
        )
        val body = request.bodyAsJson()
        val payload = body["payload"]
        val aliasedMessages = payload["messages_alias"].elements().asSequence().toList()
        val payloadItems = payload["items"].elements().asSequence().toList()
        val nestedMessageItemValues = payloadItems[1]["value"].elements().asSequence().toList()

        assertThat(request.header("Authorization")).isEqualTo("Bearer secret-key")
        assertThat(payload["prompt_alias"].asText())
            .isEqualTo("System instructions\n\nWrite a test")
        assertThat(aliasedMessages)
            .extracting({ it["role"].asText() }, { it["content"].asText() })
            .containsExactly(
                tuple("system", "System instructions"),
                tuple("user", "Write a test")
            )
        assertThat(payloadItems)
            .extracting(
                { it["kind"].asText() },
                { it["value"].isArray },
                { it["value"].isTextual }
            )
            .containsExactly(
                tuple("prompt", false, true),
                tuple("messages", true, false)
            )
        assertThat(payloadItems[0]["value"].asText())
            .isEqualTo("System instructions\n\nWrite a test")
        assertThat(nestedMessageItemValues)
            .extracting(Function { it["role"].asText() })
            .containsExactly("system", "user")
    }

    @Test
    fun `should preserve nested arrays and objects in chat request body`() {
        val settings = CustomServiceChatCompletionSettingsState().apply {
            url = "https://example.com/v1/chat/completions"
            headers = mutableMapOf("X-Test" to "true")
            body = mutableMapOf(
                "model" to "gpt-test",
                "config" to mapOf(
                    "temperature" to 0.2,
                    "enabled" to true,
                    "tags" to listOf("alpha", 2, false),
                    "nested" to mapOf("arr" to listOf(mapOf("k" to "v")))
                )
            )
        }
        val messages = listOf<OpenAIChatCompletionMessage>(
            OpenAIChatCompletionStandardMessage("user", "hello")
        )

        val request = CustomOpenAIRequestFactory.buildCustomOpenAIChatCompletionRequest(
            settings = settings,
            messages = messages,
            streamRequest = true,
            credential = null,
        )
        val body = request.bodyAsJson()
        val tags = body["config"]["tags"].elements().asSequence().toList()
        val nestedArray = body["config"]["nested"]["arr"].elements().asSequence().toList()

        assertThat(body["config"]["temperature"].asDouble()).isEqualTo(0.2)
        assertThat(body["config"]["enabled"].asBoolean()).isTrue()
        assertThat(tags)
            .extracting({ it.nodeType.name }, { it.asText() })
            .containsExactly(
                tuple("STRING", "alpha"),
                tuple("NUMBER", "2"),
                tuple("BOOLEAN", "false")
            )
        assertThat(nestedArray)
            .extracting(Function { it["k"].asText() })
            .containsExactly("v")
    }

    @Test
    fun `should replace nested stream flags and nested api key placeholders when stream is disabled`() {
        val settings = CustomServiceChatCompletionSettingsState().apply {
            url = "https://example.com/v1/chat/completions"
            headers = mutableMapOf("X-Test" to "true")
            body = mutableMapOf(
                "model" to "gpt-test",
                "stream" to "true",
                "config" to mapOf(
                    "stream" to "true",
                    "authorization" to $$"Bearer $CUSTOM_SERVICE_API_KEY"
                ),
                "items" to listOf(
                    mapOf("stream" to "true"),
                    $$"token:$CUSTOM_SERVICE_API_KEY"
                )
            )
        }
        val messages = listOf<OpenAIChatCompletionMessage>(
            OpenAIChatCompletionStandardMessage("user", "hello")
        )

        val request = CustomOpenAIRequestFactory.buildCustomOpenAIChatCompletionRequest(
            settings = settings,
            messages = messages,
            streamRequest = false,
            credential = "secret-key",
        )
        val body = request.bodyAsJson()

        assertThat(listOf(body["stream"], body["config"]["stream"], body["items"][0]["stream"]))
            .extracting(Function { it.asBoolean() })
            .containsExactly(false, false, false)
        assertThat(listOf(body["config"]["authorization"], body["items"][1]))
            .extracting(Function { it.asText() })
            .containsExactly("Bearer secret-key", "token:secret-key")
    }

    private fun Request.bodyAsJson(): JsonNode {
        val requestBody = requireNotNull(body) { "Request body is null" }
        val buffer = Buffer()
        requestBody.writeTo(buffer)
        return jacksonObjectMapper().readTree(buffer.readUtf8())
    }
}
