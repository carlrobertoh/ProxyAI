package ee.carlrobert.codegpt.completions

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.llama.PromptTemplate.LLAMA
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.llm.client.http.RequestEntity
import ee.carlrobert.llm.client.http.exchange.NdJsonStreamHttpExchange
import ee.carlrobert.llm.client.http.exchange.StreamHttpExchange
import ee.carlrobert.llm.client.util.JSONUtil.*
import org.apache.http.HttpHeaders
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest

class DefaultToolwindowChatCompletionRequestHandlerTest : IntegrationTest() {

    fun testOpenAIChatCompletionCall() {
        useOpenAIService()
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"
        val message = Message("TEST_PROMPT")
        val conversation = ConversationService.getInstance().startConversation()
        expectOpenAI(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.headers[HttpHeaders.AUTHORIZATION]!![0]).isEqualTo("Bearer TEST_API_KEY")
            assertThat(request.body)
                .extracting(
                    "model",
                    "messages"
                )
                .containsExactly(
                    "gpt-4",
                    listOf(
                        mapOf("role" to "system", "content" to "TEST_SYSTEM_PROMPT"),
                        mapOf("role" to "user", "content" to "TEST_PROMPT")
                    )
                )
            listOf(
                jsonMapResponse(
                    "choices",
                    jsonArray(jsonMap("delta", jsonMap("role", "assistant")))
                ),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "Hel")))),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "lo")))),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "!"))))
            )
        })
        val requestHandler =
            ToolwindowChatCompletionRequestHandler(getRequestEventListener(message))

        requestHandler.call(ChatCompletionParameters.builder(conversation, message).build())

        waitExpecting { "Hello!" == message.response }
    }

    fun testAzureChatCompletionCall() {
        useAzureService()
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"
        val conversationService = ConversationService.getInstance()
        val prevMessage = Message("TEST_PREV_PROMPT")
        prevMessage.response = "TEST_PREV_RESPONSE"
        val conversation = conversationService.startConversation()
        conversation.addMessage(prevMessage)
        conversationService.saveConversation(conversation)
        expectAzure(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo(
                "/openai/deployments/TEST_DEPLOYMENT_ID/chat/completions"
            )
            assertThat(request.uri.query).isEqualTo("api-version=TEST_API_VERSION")
            assertThat(request.headers["Api-key"]!![0]).isEqualTo("TEST_API_KEY")
            assertThat(request.headers["X-llm-application-tag"]!![0]).isEqualTo("codegpt")
            assertThat(request.body)
                .extracting("messages")
                .isEqualTo(
                    listOf(
                        mapOf("role" to "system", "content" to "TEST_SYSTEM_PROMPT"),
                        mapOf("role" to "user", "content" to "TEST_PREV_PROMPT"),
                        mapOf("role" to "assistant", "content" to "TEST_PREV_RESPONSE"),
                        mapOf("role" to "user", "content" to "TEST_PROMPT")
                    )
                )
            listOf(
                jsonMapResponse(
                    "choices",
                    jsonArray(jsonMap("delta", jsonMap("role", "assistant")))
                ),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "Hel")))),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "lo")))),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "!"))))
            )
        })
        val message = Message("TEST_PROMPT")
        val requestHandler =
            ToolwindowChatCompletionRequestHandler(getRequestEventListener(message))

        requestHandler.call(ChatCompletionParameters.builder(conversation, message).build())

        waitExpecting { "Hello!" == message.response }
    }

    fun testLlamaChatCompletionCall() {
        useLlamaService()
        service<ConfigurationSettings>().state.maxTokens = 99
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"
        val message = Message("TEST_PROMPT")
        val conversation = ConversationService.getInstance().startConversation()
        conversation.addMessage(Message("Ping", "Pong"))
        expectLlama(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/completion")
            assertThat(request.body)
                .extracting(
                    "prompt",
                    "n_predict",
                    "stream"
                )
                .containsExactly(
                    LLAMA.buildPrompt(
                        "TEST_SYSTEM_PROMPT",
                        "TEST_PROMPT",
                        conversation.messages
                    ),
                    99,
                    true
                )
            listOf<String?>(
                jsonMapResponse("content", "Hel"),
                jsonMapResponse("content", "lo!"),
                jsonMapResponse(
                    e("content", ""),
                    e("stop", true)
                )
            )
        })
        val requestHandler =
            ToolwindowChatCompletionRequestHandler(getRequestEventListener(message))

        requestHandler.call(ChatCompletionParameters.builder(conversation, message).build())

        waitExpecting { "Hello!" == message.response }
    }

    fun testOllamaChatCompletionCall() {
        useOllamaService()
        service<ConfigurationSettings>().state.maxTokens = 99
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"
        val message = Message("TEST_PROMPT")
        val conversation = ConversationService.getInstance().startConversation()
        expectOllama(NdJsonStreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.headers[HttpHeaders.AUTHORIZATION]!![0]).isEqualTo("Bearer TEST_API_KEY")
            assertThat(request.body)
                .extracting(
                    "model",
                    "messages"
                )
                .containsExactly(
                    HuggingFaceModel.LLAMA_3_8B_Q6_K.code,
                    listOf(
                        mapOf("role" to "system", "content" to "TEST_SYSTEM_PROMPT"),
                        mapOf("role" to "user", "content" to "TEST_PROMPT")
                    )
                )
            listOf(
                jsonMapResponse(
                    "choices",
                    jsonArray(jsonMap("delta", jsonMap("role", "assistant")))
                ),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "Hel")))),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "lo")))),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "!"))))
            )
        })
        val requestHandler =
            ToolwindowChatCompletionRequestHandler(getRequestEventListener(message))

        requestHandler.call(ChatCompletionParameters.builder(conversation, message).build())

        waitExpecting { "Hello!" == message.response }
    }

    fun testGoogleChatCompletionCall() {
        useGoogleService()
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"
        val message = Message("TEST_PROMPT")
        val conversation = ConversationService.getInstance().startConversation()
        expectGoogle(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/models/gemini-pro:streamGenerateContent")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.uri.query).isEqualTo("key=TEST_API_KEY&alt=sse")
            assertThat(request.body)
                .extracting("contents", "systemInstruction")
                .containsExactly(
                    listOf(
                        mapOf("parts" to listOf(mapOf("text" to "TEST_PROMPT")), "role" to "user"),
                    ),
                    mapOf("parts" to listOf(mapOf("text" to "TEST_SYSTEM_PROMPT")))
                )
            listOf(
                jsonMapResponse(
                    "candidates",
                    jsonArray(
                        jsonMap(
                            "content",
                            jsonMap("parts", jsonArray(jsonMap("text", "Hello")))
                        )
                    )
                ),
                jsonMapResponse(
                    "candidates",
                    jsonArray(jsonMap("content", jsonMap("parts", jsonArray(jsonMap("text", "!")))))
                )
            )
        })
        val requestHandler =
            ToolwindowChatCompletionRequestHandler(getRequestEventListener(message))

        requestHandler.call(ChatCompletionParameters.builder(conversation, message).build())

        waitExpecting { "Hello!" == message.response }
    }

    fun testCodeGPTServiceChatCompletionCall() {
        useCodeGPTService()
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"
        val message = Message("TEST_PROMPT")
        val conversation = ConversationService.getInstance().startConversation()
        expectCodeGPT(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.headers[HttpHeaders.AUTHORIZATION]!![0]).isEqualTo("Bearer TEST_API_KEY")
            assertThat(request.body)
                .extracting(
                    "model",
                    "messages"
                )
                .containsExactly(
                    "TEST_MODEL",
                    listOf(
                        mapOf("role" to "system", "content" to "TEST_SYSTEM_PROMPT"),
                        mapOf("role" to "user", "content" to "TEST_PROMPT")
                    )
                )
            listOf(
                jsonMapResponse(
                    "choices",
                    jsonArray(jsonMap("delta", jsonMap("role", "assistant")))
                ),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "Hel")))),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "lo")))),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "!"))))
            )
        })
        val requestHandler =
            ToolwindowChatCompletionRequestHandler(getRequestEventListener(message))

        requestHandler.call(ChatCompletionParameters.builder(conversation, message).build())

        waitExpecting { "Hello!" == message.response }
    }

    private fun getRequestEventListener(message: Message): CompletionResponseEventListener {
        return object : CompletionResponseEventListener {
            override fun handleCompleted(
                fullMessage: String,
                callParameters: ChatCompletionParameters
            ) {
                message.response = fullMessage
            }
        }
    }
}
