package ee.carlrobert.codegpt.agent

import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.*
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.settings.models.ModelCatalog
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import testsupport.http.RequestEntity
import testsupport.http.ResponseEntity
import testsupport.http.exchange.BasicHttpExchange
import testsupport.http.exchange.StreamHttpExchange
import testsupport.json.JSONUtil.e
import testsupport.json.JSONUtil.jsonArray
import testsupport.json.JSONUtil.jsonMap
import testsupport.json.JSONUtil.jsonMapResponse
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

class AgentProviderIntegrationTest : IntegrationTest() {

    fun testOpenAIAgentUsesMockHarnessThroughRealExecutor() {
        setCredential(OpenaiApiKey, "TEST_API_KEY")
        service<ModelSettings>().setModel(FeatureType.AGENT, "gpt-4.1-mini", ServiceType.OPENAI)
        expectOpenAI(StreamHttpExchange { request ->
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractPromptText(request)).contains("Say hello from OpenAI")
            when (request.uri.path) {
                "/v1/chat/completions" -> chatCompletionChunks("gpt-4.1-mini", "Hello from OpenAI")
                "/v1/responses" -> openAiResponsesChunks()
                else -> error("Unexpected OpenAI path: ${request.uri.path}")
            }
        })

        val result = runAgent(ServiceType.OPENAI, "Say hello from OpenAI")

        assertThat(result.output).isEqualTo("Hello from OpenAI")
        assertThat(result.events.text.toString()).isEqualTo("Hello from OpenAI")
    }

    fun testProxyAIAgentUsesMockHarnessThroughRealExecutor() {
        setCredential(CodeGptApiKey, "TEST_API_KEY")
        service<ModelSettings>().setModel(
            FeatureType.AGENT,
            ModelCatalog.PROXYAI_AUTO,
            ServiceType.PROXYAI
        )
        val fixture = createReadFixture("ProxyAI fixture")
        expectCodeGPT { request ->
            assertThat(request.uri.path).isEqualTo("/v2/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractPromptText(request)).contains("Read the fixture and repeat its contents")
            proxyAiChatCompletionToolCallChunks("""{"file_path":"${fixture.path}"}""")
        }
        expectCodeGPT { request ->
            assertThat(request.uri.path).isEqualTo("/v2/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body.toString()).contains(fixture.renderedResult)
            proxyAiChatCompletionChunks("ProxyAI read: ${fixture.contents}")
        }

        val result = runAgent(ServiceType.PROXYAI, "Read the fixture and repeat its contents")

        assertThat(result.output).isEqualTo("ProxyAI read: ${fixture.contents}")
        assertThat(result.events.text.toString()).isEqualTo("ProxyAI read: ${fixture.contents}")
    }

    fun testAnthropicAgentCompletesReadToolLoopThroughRealExecutor() {
        setCredential(AnthropicApiKey, "TEST_API_KEY")
        service<ModelSettings>().setModel(
            FeatureType.AGENT,
            "claude-haiku-4-5",
            ServiceType.ANTHROPIC
        )
        val fixture = createReadFixture("Anthropic fixture")
        expectAnthropic { request ->
            assertThat(request.uri.path).isEqualTo("/v1/messages")
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractPromptText(request)).contains("Read the fixture and repeat its contents")
            anthropicToolUseChunks("""{"file_path":"${fixture.path}"}""")
        }
        expectAnthropic { request ->
            assertThat(request.uri.path).isEqualTo("/v1/messages")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body.toString()).contains(fixture.renderedResult)
            anthropicTextChunks("Anthropic read: ${fixture.contents}")
        }

        val result = runAgent(ServiceType.ANTHROPIC, "Read the fixture and repeat its contents")

        assertThat(result.output).isEqualTo("Anthropic read: ${fixture.contents}")
        assertThat(result.events.text.toString()).isEqualTo("Anthropic read: ${fixture.contents}")
    }

    fun testGoogleAgentUsesMockHarnessThroughRealExecutor() {
        setCredential(GoogleApiKey, "TEST_API_KEY")
        service<ModelSettings>().setModel(
            FeatureType.AGENT,
            "gemini-3-flash-preview",
            ServiceType.GOOGLE
        )
        expectGoogle { request ->
            assertThat(request.uri.path).isEqualTo("/v1beta/models/gemini-3-flash-preview:generateContent")
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractGooglePromptText(request)).contains("Say hello from Google")
            ResponseEntity(
                googleTextResponse()
            )
        }

        val result = runAgent(ServiceType.GOOGLE, "Say hello from Google")

        assertThat(result.output).isEqualTo("Hello from Google")
        assertThat(result.events.text.toString()).isEqualTo("Hello from Google")
    }

    fun testMistralAgentUsesMockHarnessThroughRealExecutor() {
        setCredential(MistralApiKey, "TEST_API_KEY")
        service<ModelSettings>().setModel(FeatureType.AGENT, "devstral-2512", ServiceType.MISTRAL)
        expectMistral(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractPromptText(request)).contains("Say hello from Mistral")
            chatCompletionChunks("devstral-2512", "Hello from Mistral")
        })

        val result = runAgent(ServiceType.MISTRAL, "Say hello from Mistral")

        assertThat(result.output).isEqualTo("Hello from Mistral")
        assertThat(result.events.text.toString()).isEqualTo("Hello from Mistral")
    }

    fun testInceptionAgentUsesMockHarnessThroughRealExecutor() {
        setCredential(InceptionApiKey, "TEST_API_KEY")
        service<ModelSettings>().setModel(FeatureType.AGENT, "mercury", ServiceType.INCEPTION)
        expectInception(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractPromptText(request)).contains("Say hello from Inception")
            chatCompletionChunks("mercury", "Hello from Inception")
        })

        val result = runAgent(ServiceType.INCEPTION, "Say hello from Inception")

        assertThat(result.output).isEqualTo("Hello from Inception")
        assertThat(result.events.text.toString()).isEqualTo("Hello from Inception")
    }

    fun testCustomOpenAIAgentUsesMockHarnessThroughRealExecutor() {
        val customService = configureCustomOpenAIService()
        expectCustomOpenAI(BasicHttpExchange { request ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractPromptText(request)).contains("Say hello from Custom OpenAI")
            ResponseEntity(
                customOpenAiResponse()
            )
        })

        val result = runAgent(ServiceType.CUSTOM_OPENAI, "Say hello from Custom OpenAI")

        assertThat(customService.id).isNotBlank()
        assertThat(result.output).isEqualTo("Hello from Custom OpenAI")
        assertThat(result.events.text.toString()).isEqualTo("Hello from Custom OpenAI")
    }

    private fun runAgent(
        provider: ServiceType,
        userMessage: String
    ): AgentRunResult {
        val events = RecordingAgentEvents()
        val checkpointStorage =
            JVMFilePersistenceStorageProvider(createTempDirectory("agent-provider-test"))
        val service = ProxyAIAgent.createService(
            project = project,
            checkpointStorage = checkpointStorage,
            provider = provider,
            events = events,
            sessionId = "session-${UUID.randomUUID()}",
            pendingMessages = ConcurrentHashMap()
        )

        return try {
            val output = runBlocking {
                val agent = service.createAgent()
                agent.run(MessageWithContext(userMessage))
            }
            AgentRunResult(output, events)
        } finally {
            runBlocking { service.closeAll() }
        }
    }

    private fun chatCompletionChunks(model: String, text: String): List<String> {
        val chunks = text.chunked(4)
        return chunks.mapIndexed { index, chunk ->
            jsonMapResponse(
                e("id", "chatcmpl-test"),
                e("object", "chat.completion.chunk"),
                e("created", 1),
                e("model", model),
                e(
                    "choices",
                    jsonArray(
                        jsonMap(
                            e("index", 0),
                            e(
                                "delta",
                                jsonMap(
                                    *buildList {
                                        if (index == 0) add(e("role", "assistant"))
                                        add(e("content", chunk))
                                    }.toTypedArray()
                                )
                            ),
                            e("finishReason", null)
                        )
                    )
                )
            )
        } + jsonMapResponse(
            e("id", "chatcmpl-test"),
            e("object", "chat.completion.chunk"),
            e("created", 1),
            e("model", model),
            e(
                "choices",
                jsonArray(
                    jsonMap(
                        e("index", 0),
                        e("delta", jsonMap()),
                        e("finishReason", "stop")
                    )
                )
            )
        )
    }

    private fun openAiResponsesChunks(): List<String> {
        val model = "gpt-4.1-mini"
        val text = "Hello from OpenAI"
        val chunks = text.chunked(4)
        return chunks.mapIndexed { index, chunk ->
            jsonMapResponse(
                e("type", "response.output_text.delta"),
                e("item_id", "msg_1"),
                e("output_index", 0),
                e("content_index", 0),
                e("delta", chunk),
                e("sequence_number", index + 1)
            )
        } + jsonMapResponse(
            e("type", "response.output_item.done"),
            e("item", text),
            e("output_index", 0),
            e("sequence_number", chunks.size + 1)
        ) + jsonMapResponse(
            e("type", "response.completed"),
            e(
                "response",
                jsonMap(
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
            ),
            e("sequence_number", chunks.size + 2)
        )
    }

    private fun anthropicTextChunks(text: String): List<String> {
        return listOf(
            jsonMapResponse(
                e("type", "content_block_start"),
                e("index", 0),
                e(
                    "content_block",
                    jsonMap(
                        e("type", "text"),
                        e("text", text)
                    )
                )
            ),
            jsonMapResponse(
                e("type", "message_delta"),
                e(
                    "delta",
                    jsonMap(
                        e("stop_reason", "end_turn")
                    )
                )
            )
        )
    }

    private fun anthropicToolUseChunks(arguments: String): List<String> {
        val toolId = "toolu_anthropic_read"
        val toolName = "Read"
        return listOf(
            jsonMapResponse(
                e("type", "content_block_start"),
                e("index", 0),
                e(
                    "content_block",
                    jsonMap(
                        e("type", "tool_use"),
                        e("id", toolId),
                        e("name", toolName),
                        e("input", jsonMap())
                    )
                )
            ),
            jsonMapResponse(
                e("type", "content_block_delta"),
                e("index", 0),
                e(
                    "delta",
                    jsonMap(
                        e("type", "input_json_delta"),
                        e("partial_json", arguments)
                    )
                )
            ),
            jsonMapResponse(
                e("type", "content_block_stop"),
                e("index", 0),
                e(
                    "delta",
                    jsonMap(
                        e("type", "input_json_delta")
                    )
                )
            ),
            jsonMapResponse(
                e("type", "message_delta"),
                e(
                    "delta",
                    jsonMap(
                        e("stop_reason", "tool_use")
                    )
                )
            )
        )
    }

    private fun proxyAiChatCompletionChunks(text: String): List<String> {
        val model = "auto"
        val chunks = text.chunked(4)
        return chunks.mapIndexed { index, chunk ->
            jsonMapResponse(
                e("id", "proxyai-test"),
                e("object", "chat.completion.chunk"),
                e("created", 1),
                e("model", model),
                e(
                    "choices",
                    jsonArray(
                        jsonMap(
                            e(
                                "delta",
                                jsonMap(
                                    *buildList {
                                        if (index == 0) add(e("role", "assistant"))
                                        add(e("content", chunk))
                                    }.toTypedArray()
                                )
                            ),
                            e("finishReason", null),
                            e("nativeFinishReason", null)
                        )
                    )
                )
            )
        } + jsonMapResponse(
            e("id", "proxyai-test"),
            e("object", "chat.completion.chunk"),
            e("created", 1),
            e("model", model),
            e(
                "choices",
                jsonArray(
                    jsonMap(
                        e("delta", jsonMap()),
                        e("finishReason", "stop"),
                        e("nativeFinishReason", "stop")
                    )
                )
            )
        )
    }

    private fun proxyAiChatCompletionToolCallChunks(arguments: String): List<String> {
        val model = "auto"
        val toolId = "call_proxyai_read"
        val toolName = "Read"
        return listOf(
            jsonMapResponse(
                e("id", "proxyai-tool-call"),
                e("object", "chat.completion.chunk"),
                e("created", 1),
                e("model", model),
                e(
                    "choices",
                    jsonArray(
                        jsonMap(
                            e(
                                "delta",
                                jsonMap(
                                    e("role", "assistant"),
                                    e(
                                        "tool_calls",
                                        jsonArray(
                                            jsonMap(
                                                e("index", 0),
                                                e("id", toolId),
                                                e(
                                                    "function",
                                                    jsonMap(
                                                        e("name", toolName),
                                                        e("arguments", arguments)
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            ),
                            e("finishReason", null),
                            e("nativeFinishReason", null)
                        )
                    )
                )
            ),
            jsonMapResponse(
                e("id", "proxyai-tool-call"),
                e("object", "chat.completion.chunk"),
                e("created", 1),
                e("model", model),
                e(
                    "choices",
                    jsonArray(
                        jsonMap(
                            e("delta", jsonMap()),
                            e("finishReason", "tool_calls"),
                            e("nativeFinishReason", "tool_calls")
                        )
                    )
                )
            )
        )
    }

    private fun googleTextResponse(): String {
        val text = "Hello from Google"
        return jsonMapResponse(
            e(
                "candidates",
                jsonArray(
                    jsonMap(
                        e(
                            "content",
                            jsonMap(
                                e("role", "model"),
                                e(
                                    "parts",
                                    jsonArray(
                                        jsonMap(e("text", text))
                                    )
                                )
                            )
                        ),
                        e("finishReason", "STOP")
                    )
                )
            ),
            e(
                "usageMetadata",
                jsonMap(
                    e("promptTokenCount", 1),
                    e("candidatesTokenCount", 1),
                    e("totalTokenCount", 2)
                )
            )
        )
    }

    private fun customOpenAiResponse(): String {
        val model = "custom-agent-model"
        val text = "Hello from Custom OpenAI"
        return jsonMapResponse(
            e(
                "choices",
                jsonArray(
                    jsonMap(
                        e("finishReason", "stop"),
                        e(
                            "message",
                            jsonMap(
                                e("role", "assistant"),
                                e("content", text)
                            )
                        )
                    )
                )
            ),
            e("created", 1),
            e("id", "custom-openai-test"),
            e("model", model),
            e("object", "chat.completion"),
            e(
                "usage",
                jsonMap(
                    e("prompt_tokens", 1),
                    e("completion_tokens", 1),
                    e("total_tokens", 2)
                )
            )
        )
    }

    private fun createReadFixture(contents: String): ReadFixture {
        val path = createTempFile("agent-provider-read-", ".txt")
        path.writeText(contents)
        return ReadFixture(
            path = path.toAbsolutePath().toString(),
            contents = contents,
            renderedResult = "1\t$contents"
        )
    }

    private fun configureCustomOpenAIService(): CustomServiceSettingsState {
        val settings = service<CustomServicesSettings>()
        val serviceState = CustomServiceSettingsState().apply {
            name = "Agent Test Custom Service"
            chatCompletionSettings.url =
                System.getProperty("customOpenAI.baseUrl") + "/v1/chat/completions"
            chatCompletionSettings.headers.clear()
            chatCompletionSettings.body.clear()
            chatCompletionSettings.body["model"] = "custom-agent-model"
        }
        settings.state.services.clear()
        settings.state.services.add(serviceState)
        setCredential(
            CustomServiceApiKeyById(requireNotNull(serviceState.id)),
            "TEST_API_KEY"
        )
        service<ModelSettings>().setModel(
            FeatureType.AGENT,
            serviceState.id,
            ServiceType.CUSTOM_OPENAI
        )
        return serviceState
    }

    private fun extractPromptText(request: RequestEntity): String {
        val messages =
            request.body["messages"] as? List<*> ?: request.body["input"] as? List<*> ?: return ""
        return messages.joinToString("\n") { message ->
            when (val content = (message as? Map<*, *>)?.get("content")) {
                is String -> content
                is List<*> -> content.joinToString("\n") { part ->
                    val partMap = part as? Map<*, *>
                    (partMap?.get("text") as? String)
                        ?: (partMap?.get("value") as? String)
                        ?: partMap.toString()
                }

                null -> ""
                else -> content.toString()
            }
        }
    }

    private fun extractGooglePromptText(request: RequestEntity): String {
        val contents = request.body["contents"] as? List<*> ?: return ""
        return contents.joinToString("\n") { content ->
            val parts = (content as? Map<*, *>)?.get("parts") as? List<*> ?: emptyList<Any>()
            parts.joinToString("\n") { part ->
                ((part as? Map<*, *>)?.get("text") as? String).orEmpty()
            }
        }
    }

    private data class AgentRunResult(
        val output: String,
        val events: RecordingAgentEvents
    )

    private data class ReadFixture(
        val path: String,
        val contents: String,
        val renderedResult: String
    )

    private class RecordingAgentEvents : AgentEvents {
        val text = StringBuilder()

        override fun onTextReceived(text: String) {
            this.text.append(text)
        }

        override fun onQueuedMessagesResolved() = Unit
    }
}
