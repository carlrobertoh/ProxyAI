package ee.carlrobert.codegpt.completions

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.llama.PromptTemplate.LLAMA
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.prompts.PersonaPromptDetailsState
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.llm.client.http.RequestEntity
import ee.carlrobert.llm.client.http.exchange.NdJsonStreamHttpExchange
import ee.carlrobert.llm.client.http.exchange.StreamHttpExchange
import ee.carlrobert.llm.client.util.JSONUtil.*
import org.apache.http.HttpHeaders
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import ee.carlrobert.codegpt.util.file.FileUtil.getResourceContent

class DefaultToolwindowChatCompletionRequestHandlerTest : IntegrationTest() {

    fun testOpenAIChatCompletionCall() {
        useOpenAIService()
        val customPersona = PersonaPromptDetailsState().apply {
            id = 999L
            name = "Test Persona"
            instructions = "TEST_SYSTEM_PROMPT"
        }
        service<PromptsSettings>().state.personas.selectedPersona = customPersona
        val message = Message("TEST_PROMPT")
        val conversation = ConversationService.getInstance(project).startConversation()
        expectOpenAI(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.headers[HttpHeaders.AUTHORIZATION]!![0]).isEqualTo("Bearer TEST_API_KEY")
            val guidelines = getResourceContent("/prompts/persona/psi-navigation-guidelines.txt")
            val expectedSystem = "TEST_SYSTEM_PROMPT\n$guidelines"
            assertThat(request.body)
                .extracting(
                    "model",
                    "messages"
                )
                .containsExactly(
                    "gpt-4o",
                    listOf(
                        mapOf("role" to "system", "content" to expectedSystem),
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
            ToolwindowChatCompletionRequestHandler(project, getRequestEventListener(message))

        requestHandler.call(ChatCompletionParameters.builder(conversation, message).build())

        waitExpecting { "Hello!" == message.response }
    }

    fun testLlamaChatCompletionCall() {
        useLlamaService()
        service<ConfigurationSettings>().state.maxTokens = 99
        val customPersona = PersonaPromptDetailsState().apply {
            id = 999L
            name = "Test Persona"
            instructions = "TEST_SYSTEM_PROMPT"
        }
        service<PromptsSettings>().state.personas.selectedPersona = customPersona
        val message = Message("TEST_PROMPT")
        val conversation = ConversationService.getInstance(project).startConversation()
        conversation.addMessage(Message("Ping", "Pong"))
        expectLlama(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            val guidelines = getResourceContent("/prompts/persona/psi-navigation-guidelines.txt")
            val expectedSystem = "TEST_SYSTEM_PROMPT\n$guidelines"
            assertThat(request.body)
                .extracting(
                    "model",
                    "messages"
                )
                .containsExactly(
                    HuggingFaceModel.CODE_LLAMA_7B_Q4.code,
                    listOf(
                        mapOf("role" to "system", "content" to expectedSystem),
                        mapOf("role" to "user", "content" to "Ping"),
                        mapOf("role" to "assistant", "content" to "Pong"),
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
            ToolwindowChatCompletionRequestHandler(project, getRequestEventListener(message))

        requestHandler.call(ChatCompletionParameters.builder(conversation, message).build())

        waitExpecting { "Hello!" == message.response }
    }

    fun testOllamaChatCompletionCall() {
        useOllamaService()
        service<ConfigurationSettings>().state.maxTokens = 99
        val customPersona = PersonaPromptDetailsState().apply {
            id = 999L
            name = "Test Persona"
            instructions = "TEST_SYSTEM_PROMPT"
        }
        service<PromptsSettings>().state.personas.selectedPersona = customPersona
        val message = Message("TEST_PROMPT")
        val conversation = ConversationService.getInstance(project).startConversation()
        expectOllama(NdJsonStreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.headers[HttpHeaders.AUTHORIZATION]!![0]).isEqualTo("Bearer TEST_API_KEY")
            val guidelines = getResourceContent("/prompts/persona/psi-navigation-guidelines.txt")
            val expectedSystem = "TEST_SYSTEM_PROMPT\n$guidelines"
            assertThat(request.body)
                .extracting(
                    "model",
                    "messages"
                )
                .containsExactly(
                    HuggingFaceModel.LLAMA_3_8B_Q6_K.code,
                    listOf(
                        mapOf("role" to "system", "content" to expectedSystem),
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
            ToolwindowChatCompletionRequestHandler(project, getRequestEventListener(message))

        requestHandler.call(ChatCompletionParameters.builder(conversation, message).build())

        waitExpecting { "Hello!" == message.response }
    }

    fun testGoogleChatCompletionCall() {
        useGoogleService()
        service<ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings>().state
            .chatCompletionSettings.clickableLinksEnabled = true
        val customPersona = PersonaPromptDetailsState().apply {
            id = 999L
            name = "Test Persona"
            instructions = "TEST_SYSTEM_PROMPT"
        }
        service<PromptsSettings>().state.personas.selectedPersona = customPersona
        val message = Message("TEST_PROMPT")
        val conversation = ConversationService.getInstance(project).startConversation()
        expectGoogle(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/models/gemini-2.0-flash:streamGenerateContent")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.uri.query).isEqualTo("key=TEST_API_KEY&alt=sse")
            val guidelines = getResourceContent("/prompts/persona/psi-navigation-guidelines.txt")
            val expectedSystem = "TEST_SYSTEM_PROMPT\n$guidelines"
            assertThat(request.body)
                .extracting("contents", "systemInstruction")
                .containsExactly(
                    listOf(
                        mapOf("parts" to listOf(mapOf("text" to "TEST_PROMPT")), "role" to "user"),
                    ),
                    mapOf("parts" to listOf(mapOf("text" to expectedSystem)))
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
            ToolwindowChatCompletionRequestHandler(project, getRequestEventListener(message))

        requestHandler.call(ChatCompletionParameters.builder(conversation, message).build())

        waitExpecting { "Hello!" == message.response }
    }

    fun testCodeGPTServiceChatCompletionCall() {
        useCodeGPTService()
        val customPersona = PersonaPromptDetailsState().apply {
            id = 999L
            name = "Test Persona"
            instructions = "TEST_SYSTEM_PROMPT"
        }
        service<PromptsSettings>().state.personas.selectedPersona = customPersona
        val message = Message("TEST_PROMPT")
        val conversation = ConversationService.getInstance(project).startConversation()
        expectCodeGPT(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.headers[HttpHeaders.AUTHORIZATION]!![0]).isEqualTo("Bearer TEST_API_KEY")
            val guidelines = getResourceContent("/prompts/persona/psi-navigation-guidelines.txt")
            val expectedSystem = "TEST_SYSTEM_PROMPT\n$guidelines"
            assertThat(request.body)
                .extracting(
                    "model",
                    "messages"
                )
                .containsExactly(
                    "gpt-5-mini",
                    listOf(
                        mapOf("role" to "user", "content" to expectedSystem),
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
            ToolwindowChatCompletionRequestHandler(project, getRequestEventListener(message))

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
