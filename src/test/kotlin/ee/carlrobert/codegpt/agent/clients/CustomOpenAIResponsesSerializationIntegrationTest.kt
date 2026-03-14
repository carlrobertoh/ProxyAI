package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import testsupport.http.ResponseEntity
import testsupport.http.exchange.BasicHttpExchange
import testsupport.json.JSONUtil.e
import testsupport.json.JSONUtil.jsonArray
import testsupport.json.JSONUtil.jsonMap
import testsupport.json.JSONUtil.jsonMapResponse

class CustomOpenAIResponsesSerializationIntegrationTest : IntegrationTest() {

    fun testResponsesRequestsFlattenAdditionalBodyParameters() {
        runBlocking {
            val settings = CustomServiceChatCompletionSettingsState().apply {
                url = System.getProperty("customOpenAI.baseUrl") + "/v1/responses"
                headers.clear()
                body.clear()
                body["model"] = "custom-responses-model"
                body["input"] = "\$OPENAI_MESSAGES"
                body["custom_config"] = mapOf("tier" to "gold")
                body["extra_flag"] = "enabled"
            }
            val client = CustomOpenAILLMClient.fromSettingsState("TEST_API_KEY", settings)
            val model = LLModel(
                id = "custom-responses-model",
                provider = CustomOpenAILLMClient.CustomOpenAI,
                capabilities = listOf(LLMCapability.OpenAIEndpoint.Responses),
                contextLength = 128_000,
                maxOutputTokens = 4_096
            )
            val prompt = prompt("custom-openai-responses-serialization") {
                user("Test flattened params")
            }

            expectCustomOpenAI(BasicHttpExchange { request ->
                assertThat(request.uri.path).isEqualTo("/v1/responses")
                assertThat(request.method).isEqualTo("POST")
                assertThat(request.body)
                    .containsEntry("extra_flag", "enabled")
                    .containsKey("custom_config")
                    .doesNotContainKey("additional_properties")
                assertThat(request.body["custom_config"])
                    .isEqualTo(mapOf("tier" to "gold"))
                ResponseEntity(
                    openAiResponsesResponse(
                        model = "custom-responses-model",
                        text = "Hello with flattened params"
                    )
                )
            })

            val response = client.execute(prompt, model, emptyList())

            assertThat(response)
                .singleElement()
                .extracting("content")
                .isEqualTo("Hello with flattened params")
        }
    }

    private fun openAiResponsesResponse(
        model: String,
        text: String
    ): String {
        return jsonMapResponse(
            e("id", "resp-openai-test"),
            e("object", "response"),
            e("created_at", 1),
            e("model", model),
            e(
                "output",
                jsonArray(
                    jsonMap(
                        e("type", "message"),
                        e("id", "msg_1"),
                        e("role", "assistant"),
                        e("status", "completed"),
                        e(
                            "content",
                            jsonArray(
                                jsonMap(
                                    e("type", "output_text"),
                                    e("text", text),
                                    e("annotations", jsonArray())
                                )
                            )
                        )
                    )
                )
            ),
            e("parallel_tool_calls", true),
            e("status", "completed"),
            e("text", jsonMap()),
            e(
                "usage",
                jsonMap(
                    e("input_tokens", 1),
                    e("input_tokens_details", jsonMap("cached_tokens", 0)),
                    e("output_tokens", 1),
                    e("output_tokens_details", jsonMap("reasoning_tokens", 0)),
                    e("total_tokens", 2)
                )
            )
        )
    }
}
