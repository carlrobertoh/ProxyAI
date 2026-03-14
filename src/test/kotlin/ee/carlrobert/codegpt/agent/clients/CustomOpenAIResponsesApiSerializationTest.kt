package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.executor.clients.openai.base.models.Content
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolFunction
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CustomOpenAIResponsesApiSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `responses api request should encode tools with top level name`() {
        val state = CustomServiceChatCompletionSettingsState().apply {
            url = "https://example.com/v1/responses"
            body.clear()
            body["stream"] = true
            body["input"] = "\$OPENAI_MESSAGES"
        }
        val client = CustomOpenAILLMClient.fromSettingsState("test-key", state)
        val serializeMethod = client.javaClass.getDeclaredMethod(
            "serializeProviderChatRequest",
            List::class.java,
            LLModel::class.java,
            List::class.java,
            OpenAIToolChoice::class.java,
            LLMParams::class.java,
            Boolean::class.javaPrimitiveType
        )
        serializeMethod.isAccessible = true

        val payload = serializeMethod.invoke(
            client,
            listOf(OpenAIMessage.User(content = Content.Text("hello"))),
            LLModel(
                id = "gpt-test",
                provider = CustomOpenAILLMClient.CustomOpenAI,
                capabilities = emptyList(),
                contextLength = 128_000,
                maxOutputTokens = 4_096
            ),
            listOf(
                OpenAITool(
                    OpenAIToolFunction(
                        name = "Diagnostics",
                        description = "Read IDE diagnostics",
                        parameters = buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {})
                            put("required", buildJsonArray {})
                        },
                        strict = true
                    )
                )
            ),
            OpenAIToolChoice.Function(OpenAIToolChoice.FunctionName("Diagnostics")),
            CustomOpenAIParams(),
            true
        ) as String

        val request = json.parseToJsonElement(payload).jsonObject
        val tool = request.getValue("tools").jsonArray.single().jsonObject
        val toolChoice = request.getValue("tool_choice").jsonObject

        assertThat(tool.getValue("type").jsonPrimitive.content).isEqualTo("function")
        assertThat(tool.getValue("name").jsonPrimitive.content).isEqualTo("Diagnostics")
        assertThat(tool.getValue("description").jsonPrimitive.content).isEqualTo("Read IDE diagnostics")
        assertThat(tool.getValue("strict").jsonPrimitive.boolean).isTrue()
        assertThat(tool).doesNotContainKey("function")
        assertThat(toolChoice.getValue("type").jsonPrimitive.content).isEqualTo("function")
        assertThat(toolChoice.getValue("name").jsonPrimitive.content).isEqualTo("Diagnostics")
    }

    @Test
    fun `responses api request should encode mode tool choice as lowercase string`() {
        val state = CustomServiceChatCompletionSettingsState().apply {
            url = "https://example.com/v1/responses"
            body.clear()
            body["stream"] = true
            body["input"] = "\$OPENAI_MESSAGES"
        }
        val client = CustomOpenAILLMClient.fromSettingsState("test-key", state)
        val serializeMethod = client.javaClass.getDeclaredMethod(
            "serializeProviderChatRequest",
            List::class.java,
            LLModel::class.java,
            List::class.java,
            OpenAIToolChoice::class.java,
            LLMParams::class.java,
            Boolean::class.javaPrimitiveType
        )
        serializeMethod.isAccessible = true

        val payload = serializeMethod.invoke(
            client,
            listOf(OpenAIMessage.User(content = Content.Text("hello"))),
            LLModel(
                id = "gpt-test",
                provider = CustomOpenAILLMClient.CustomOpenAI,
                capabilities = emptyList(),
                contextLength = 128_000,
                maxOutputTokens = 4_096
            ),
            emptyList<OpenAITool>(),
            openAIToolChoiceMode("required"),
            CustomOpenAIParams(),
            true
        ) as String

        val request = json.parseToJsonElement(payload).jsonObject

        assertThat(request.getValue("tool_choice").jsonPrimitive.content).isEqualTo("required")
    }

    @Test
    fun `responses api request should encode agent tool history as input items`() {
        val state = CustomServiceChatCompletionSettingsState().apply {
            url = "https://example.com/v1/responses"
            body.clear()
            body["stream"] = true
            body["input"] = "\$OPENAI_MESSAGES"
        }
        val client = CustomOpenAILLMClient.fromSettingsState("test-key", state)
        val serializeMethod = client.javaClass.getDeclaredMethod(
            "serializeProviderChatRequest",
            List::class.java,
            LLModel::class.java,
            List::class.java,
            OpenAIToolChoice::class.java,
            LLMParams::class.java,
            Boolean::class.javaPrimitiveType
        )
        serializeMethod.isAccessible = true

        val payload = serializeMethod.invoke(
            client,
            listOf(
                OpenAIMessage.System(content = Content.Text("System instructions")),
                OpenAIMessage.User(content = Content.Text("Read the fixture")),
                OpenAIMessage.Assistant(
                    content = Content.Text("Calling Read"),
                    toolCalls = listOf(
                        OpenAIToolCall(
                            "call_read",
                            OpenAIFunction("Read", "\"{\\\"file_path\\\":\\\"/tmp/fixture.txt\\\"}\"")
                        )
                    )
                ),
                OpenAIMessage.Tool(
                    content = Content.Text("1\tfixture contents"),
                    toolCallId = "call_read"
                )
            ),
            LLModel(
                id = "gpt-test",
                provider = CustomOpenAILLMClient.CustomOpenAI,
                capabilities = emptyList(),
                contextLength = 128_000,
                maxOutputTokens = 4_096
            ),
            emptyList<OpenAITool>(),
            null,
            CustomOpenAIParams(),
            true
        ) as String

        val request = json.parseToJsonElement(payload).jsonObject
        val input = request.getValue("input").jsonArray

        assertThat(input.map { it.jsonObject.getValue("type").jsonPrimitive.content }).containsExactly(
            "message",
            "message",
            "message",
            "function_call",
            "function_call_output"
        )
        assertThat(input[0].jsonObject.getValue("role").jsonPrimitive.content).isEqualTo("developer")
        assertThat(input[1].jsonObject.getValue("role").jsonPrimitive.content).isEqualTo("user")
        assertThat(input[2].jsonObject.getValue("role").jsonPrimitive.content).isEqualTo("assistant")
        assertThat(
            input[2].jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        ).isEqualTo("Calling Read")
        assertThat(input[3].jsonObject.getValue("name").jsonPrimitive.content).isEqualTo("Read")
        assertThat(input[3].jsonObject.getValue("call_id").jsonPrimitive.content).isEqualTo("call_read")
        assertThat(input[3].jsonObject.getValue("arguments").jsonPrimitive.content)
            .isEqualTo("""{"file_path":"/tmp/fixture.txt"}""")
        assertThat(input[4].jsonObject.getValue("call_id").jsonPrimitive.content).isEqualTo("call_read")
        assertThat(input[4].jsonObject.getValue("output").jsonPrimitive.content)
            .isEqualTo("1\tfixture contents")
    }

    private fun openAIToolChoiceMode(value: String): OpenAIToolChoice {
        val modeClass = Class.forName(
            "ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice\$Mode"
        )
        val boxMethod = modeClass.getDeclaredMethod("box-impl", String::class.java)
        return boxMethod.invoke(null, value) as OpenAIToolChoice
    }
}
