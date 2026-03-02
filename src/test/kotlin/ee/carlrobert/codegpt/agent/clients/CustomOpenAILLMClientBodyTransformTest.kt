package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CustomOpenAILLMClientBodyTransformTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `should transform nested placeholder values in custom body`() {
        val body = mapOf(
            "model" to "gpt-test",
            "payload" to mapOf(
                "prompt_alias" to $$"$PROMPT",
                "messages_alias" to $$"$OPENAI_MESSAGES",
                "items" to listOf(
                    mapOf("kind" to "prompt", "value" to $$"$PROMPT"),
                    mapOf("kind" to "messages", "value" to $$"$OPENAI_MESSAGES"),
                )
            ),
            "token" to $$"Bearer $CUSTOM_SERVICE_API_KEY",
        )

        val properties = buildCustomOpenAIAdditionalProperties(
            body = body,
            messages = listOf(
                OpenAIMessage.System(content = Content.Text("System instructions")),
                OpenAIMessage.User(content = Content.Text("Write a test")),
            ),
            streamRequest = true,
            credential = "secret-key",
            json = json
        ) ?: emptyMap()

        val payload = properties.getValue("payload").jsonObject
        val payloadItems = payload.getValue("items").jsonArray
        assertThat(properties.getValue("token").jsonPrimitive.content).isEqualTo("Bearer secret-key")
        assertThat(payload.getValue("prompt_alias").jsonPrimitive.content)
            .isEqualTo("System instructions\n\nWrite a test")
        assertThat(
            payload.getValue("messages_alias").jsonArray
                .map { message ->
                    val msg = message.jsonObject
                    msg.getValue("role").jsonPrimitive.content to msg.getValue("content").jsonPrimitive.content
                }
        ).containsExactly(
            "system" to "System instructions",
            "user" to "Write a test"
        )
        assertThat(
            payloadItems.map { item ->
                val node = item.jsonObject
                Triple(
                    node.getValue("kind").jsonPrimitive.content,
                    node.getValue("value").toString().startsWith("["),
                    node.getValue("value").toString().startsWith("\"")
                )
            }
        ).containsExactly(
            Triple("prompt", false, true),
            Triple("messages", true, false)
        )
        assertThat(payloadItems[0].jsonObject.getValue("value").jsonPrimitive.content)
            .isEqualTo("System instructions\n\nWrite a test")
        assertThat(
            payloadItems[1].jsonObject.getValue("value").jsonArray
                .map { it.jsonObject.getValue("role").jsonPrimitive.content }
        ).containsExactly("system", "user")
    }

    @Test
    fun `should preserve nested arrays and objects in custom body`() {
        val body = mapOf(
            "model" to "gpt-test",
            "config" to mapOf(
                "temperature" to 0.2,
                "enabled" to true,
                "tags" to listOf("alpha", 2, false),
                "nested" to mapOf("arr" to listOf(mapOf("k" to "v")))
            )
        )

        val properties = buildCustomOpenAIAdditionalProperties(
            body = body,
            messages = listOf(OpenAIMessage.User(content = Content.Text("hello"))),
            streamRequest = true,
            credential = "secret-key",
            json = json
        ) ?: emptyMap()

        val config = properties.getValue("config").jsonObject
        assertThat(config.getValue("temperature").jsonPrimitive.double).isEqualTo(0.2)
        assertThat(config.getValue("enabled").jsonPrimitive.boolean).isTrue()
        assertThat(
            config.getValue("tags").jsonArray
                .map { it.jsonPrimitive.content }).containsExactly("alpha", "2", "false")
        assertThat(
            config.getValue("nested").jsonObject.getValue("arr").jsonArray
                .map { it.jsonObject.getValue("k").jsonPrimitive.content }
        ).containsExactly("v")
    }

    @Test
    fun `should replace nested stream flags and api key placeholders when stream is disabled`() {
        val body = mapOf(
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

        val properties = buildCustomOpenAIAdditionalProperties(
            body = body,
            messages = listOf(OpenAIMessage.User(content = Content.Text("hello"))),
            streamRequest = false,
            credential = "secret-key",
            json = json
        ) ?: emptyMap()

        assertThat(properties).doesNotContainKey("stream")
        assertThat(
            listOf(
                properties.getValue("config").jsonObject.getValue("stream").jsonPrimitive.boolean,
                properties.getValue("items").jsonArray[0].jsonObject.getValue("stream").jsonPrimitive.boolean
            )
        ).containsExactly(false, false)
        assertThat(
            listOf(
                properties.getValue("config").jsonObject.getValue("authorization").jsonPrimitive.content,
                properties.getValue("items").jsonArray[1].jsonPrimitive.content
            )
        ).containsExactly("Bearer secret-key", "token:secret-key")
    }

    @Test
    fun `should enable custom openai streaming only when body stream is explicitly true`() {
        val defaultState = CustomServiceChatCompletionSettingsState().apply {
            body.remove("stream")
        }
        val disabledState = CustomServiceChatCompletionSettingsState().apply {
            body["stream"] = false
        }
        val enabledState = CustomServiceChatCompletionSettingsState().apply {
            body["stream"] = true
        }
        val enabledStringState = CustomServiceChatCompletionSettingsState().apply {
            body["stream"] = "true"
        }

        assertThat(defaultState.shouldStream()).isFalse()
        assertThat(disabledState.shouldStream()).isFalse()
        assertThat(enabledState.shouldStream()).isTrue()
        assertThat(enabledStringState.shouldStream()).isTrue()
    }
}
