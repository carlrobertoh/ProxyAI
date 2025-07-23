package ee.carlrobert.codegpt.toolwindow.chat

import com.intellij.openapi.components.service
import com.intellij.testFramework.LightVirtualFile
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.completions.ConversationType
import ee.carlrobert.codegpt.completions.HuggingFaceModel
import ee.carlrobert.codegpt.completions.llama.PromptTemplate.LLAMA
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.llm.client.http.RequestEntity
import ee.carlrobert.llm.client.http.exchange.StreamHttpExchange
import ee.carlrobert.llm.client.util.JSONUtil.*
import org.apache.http.HttpHeaders
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class ChatToolWindowTabPanelTest : IntegrationTest() {

    fun testSendingOpenAIMessage() {
        useOpenAIService()
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"
        val message = Message("Hello!")
        val conversation = ConversationService.getInstance().startConversation(project)
        val panel = ChatToolWindowTabPanel(project, conversation)
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
                    "gpt-4o",
                    listOf(
                        mapOf("role" to "system", "content" to "TEST_SYSTEM_PROMPT\n"),
                        mapOf("role" to "user", "content" to "Hello!")
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

        panel.sendMessage(message, ConversationType.DEFAULT)

        waitExpecting {
            val messages = conversation.messages
            messages.isNotEmpty() && "Hello!" == messages[0].response
                    && panel.tokenDetails.conversationTokens > 0
        }
        val encodingManager = EncodingManager.getInstance()
        assertThat(panel.tokenDetails).extracting(
            "systemPromptTokens",
            "conversationTokens",
            "userPromptTokens",
            "highlightedTokens"
        )
            .containsExactly(
                encodingManager.countTokens("TEST_SYSTEM_PROMPT"),
                encodingManager.countConversationTokens(conversation),
                0,
                0
            )
        assertThat(panel.conversation)
            .isNotNull()
            .extracting("id", "discardTokenLimit")
            .containsExactly(conversation.id, false)
        val messages = panel.conversation.messages
        assertThat(messages).hasSize(1)
        assertThat(messages[0])
            .extracting("id", "prompt", "response")
            .containsExactly(message.id, message.prompt, message.response)
    }

    fun testSendingOpenAIMessageWithReferencedContext() {
        useOpenAIService()
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"
        val message = Message("TEST_MESSAGE")
        message.referencedFilePaths =
            listOf("TEST_FILE_PATH_1", "TEST_FILE_PATH_2", "TEST_FILE_PATH_3")
        val conversation = ConversationService.getInstance().startConversation(project)
        val panel = ChatToolWindowTabPanel(project, conversation)
        panel.includeFiles(listOf(
            LightVirtualFile("TEST_FILE_NAME_1", "TEST_FILE_CONTENT_1"),
            LightVirtualFile("TEST_FILE_NAME_2", "TEST_FILE_CONTENT_2"),
            LightVirtualFile("TEST_FILE_NAME_3", "TEST_FILE_CONTENT_3"),
        ))
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
                    "gpt-4o",
                    listOf(
                        mapOf("role" to "system", "content" to "TEST_SYSTEM_PROMPT\n"),
                        mapOf(
                            "role" to "user",
                            "content" to """
                            Use the following context to answer question at the end:

                            ```/TEST_FILE_NAME_1:/TEST_FILE_NAME_1
                            TEST_FILE_CONTENT_1
                            ```
                            
                            
                            ```/TEST_FILE_NAME_2:/TEST_FILE_NAME_2
                            TEST_FILE_CONTENT_2
                            ```
                            
                            
                            ```/TEST_FILE_NAME_3:/TEST_FILE_NAME_3
                            TEST_FILE_CONTENT_3
                            ```
                            
                            
                            Question: TEST_MESSAGE""".trimIndent()
                        )
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

        panel.sendMessage(message, ConversationType.DEFAULT)

        waitExpecting {
            val messages = conversation.messages
            messages.isNotEmpty() && "Hello!" == messages[0].response
                    && panel.tokenDetails.conversationTokens > 0
        }
        val encodingManager = EncodingManager.getInstance()
        assertThat(panel.tokenDetails).extracting(
            "systemPromptTokens",
            "conversationTokens",
            "userPromptTokens",
            "highlightedTokens"
        )
            .containsExactly(
                encodingManager.countTokens("TEST_SYSTEM_PROMPT"),
                encodingManager.countConversationTokens(conversation),
                0,
                0
            )
        assertThat(panel.conversation)
            .isNotNull()
            .extracting("id", "discardTokenLimit")
            .containsExactly(conversation.id, false)
        val messages = panel.conversation.messages
        assertThat(messages).hasSize(1)
        assertThat(messages[0])
            .extracting("id", "prompt", "response", "referencedFilePaths")
            .containsExactly(
                message.id,
                message.prompt,
                message.response,
                listOf("TEST_FILE_PATH_1", "TEST_FILE_PATH_2", "TEST_FILE_PATH_3")
            )
    }

    fun testSendingOpenAIMessageWithImageInSession() {
        val testImagePath =
            Objects.requireNonNull(javaClass.getResource("/images/test-image.png")).path
        project.putUserData(CodeGPTKeys.IMAGE_ATTACHMENT_FILE_PATH, testImagePath)
        useOpenAIService("gpt-4-vision-preview")
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"
        val message = Message("TEST_MESSAGE")
        val conversation = ConversationService.getInstance().startConversation(project)
        val panel = ChatToolWindowTabPanel(project, conversation)
        expectOpenAI(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.headers[HttpHeaders.AUTHORIZATION]!![0]).isEqualTo("Bearer TEST_API_KEY")
            try {
                val testImageUrl = ("data:image/png;base64,"
                        + Base64.getEncoder()
                    .encodeToString(Files.readAllBytes(Path.of(testImagePath))))
                assertThat(request.body)
                    .extracting("model", "messages")
                    .containsExactly(
                        "gpt-4-vision-preview",
                        listOf(
                            mapOf("role" to "system", "content" to "TEST_SYSTEM_PROMPT\n"),
                            mapOf(
                                "role" to "user", "content" to listOf(
                                    mapOf(
                                        "type" to "image_url",
                                        "image_url" to mapOf("url" to testImageUrl)
                                    ),
                                    mapOf("type" to "text", "text" to "TEST_MESSAGE")
                                )
                            )
                        )
                    )
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
            listOf<String?>(
                jsonMapResponse(
                    "choices",
                    jsonArray(jsonMap("delta", jsonMap("role", "assistant")))
                ),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "Hel")))),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "lo")))),
                jsonMapResponse("choices", jsonArray(jsonMap("delta", jsonMap("content", "!"))))
            )
        })

        panel.sendMessage(message, ConversationType.DEFAULT)

        waitExpecting {
            val messages = conversation.messages
            messages.isNotEmpty() && "Hello!" == messages[0].response
                    && panel.tokenDetails.conversationTokens > 0
        }
        val encodingManager = EncodingManager.getInstance()
        assertThat(panel.tokenDetails).extracting(
            "systemPromptTokens",
            "conversationTokens",
            "userPromptTokens",
            "highlightedTokens"
        )
            .containsExactly(
                encodingManager.countTokens("TEST_SYSTEM_PROMPT"),
                encodingManager.countConversationTokens(conversation),
                0,
                0
            )
        assertThat(panel.conversation)
            .isNotNull()
            .extracting("id", "discardTokenLimit")
            .containsExactly(conversation.id, false)
        val messages = panel.conversation.messages
        assertThat(messages).hasSize(1)
        assertThat(messages[0])
            .extracting("id", "prompt", "response", "imageFilePath")
            .containsExactly(
                message.id,
                message.prompt,
                message.response,
                message.imageFilePath
            )
    }

    fun testFixCompileErrorsWithOpenAIService() {
        useOpenAIService()
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"
        val message = Message("TEST_MESSAGE")
        message.referencedFilePaths =
            listOf("TEST_FILE_PATH_1", "TEST_FILE_PATH_2", "TEST_FILE_PATH_3")
        val conversation = ConversationService.getInstance().startConversation(project)
        val panel = ChatToolWindowTabPanel(project, conversation)
        panel.includeFiles(
            listOf(
                LightVirtualFile("TEST_FILE_NAME_1", "TEST_FILE_CONTENT_1"),
                LightVirtualFile("TEST_FILE_NAME_2", "TEST_FILE_CONTENT_2"),
                LightVirtualFile("TEST_FILE_NAME_3", "TEST_FILE_CONTENT_3"),
            )
        )
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
                    "gpt-4o",
                    listOf(
                        mapOf(
                            "role" to "system",
                            "content" to service<PromptsSettings>().state.coreActions.fixCompileErrors.instructions
                        ),
                        mapOf(
                            "role" to "user",
                            "content" to """
                            Use the following context to answer question at the end:

                            ```/TEST_FILE_NAME_1:/TEST_FILE_NAME_1
                            TEST_FILE_CONTENT_1
                            ```
                            
                            
                            ```/TEST_FILE_NAME_2:/TEST_FILE_NAME_2
                            TEST_FILE_CONTENT_2
                            ```
                            
                            
                            ```/TEST_FILE_NAME_3:/TEST_FILE_NAME_3
                            TEST_FILE_CONTENT_3
                            ```
                            
                            
                            Question: TEST_MESSAGE""".trimIndent()
                        )
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

        panel.sendMessage(message, ConversationType.FIX_COMPILE_ERRORS)

        waitExpecting {
            val messages = conversation.messages
            messages.isNotEmpty() && "Hello!" == messages[0].response
                    && panel.tokenDetails.conversationTokens > 0
        }
        val encodingManager = EncodingManager.getInstance()
        assertThat(panel.tokenDetails).extracting(
            "systemPromptTokens",
            "conversationTokens",
            "userPromptTokens",
            "highlightedTokens"
        )
            .containsExactly(
                encodingManager.countTokens("TEST_SYSTEM_PROMPT"),
                encodingManager.countConversationTokens(conversation),
                0,
                0
            )
        assertThat(panel.conversation)
            .isNotNull()
            .extracting("id", "discardTokenLimit")
            .containsExactly(conversation.id, false)
        val messages = panel.conversation.messages
        assertThat(messages).hasSize(1)
        assertThat(messages[0])
            .extracting("id", "prompt", "response", "referencedFilePaths")
            .containsExactly(
                message.id,
                message.prompt,
                message.response,
                listOf("TEST_FILE_PATH_1", "TEST_FILE_PATH_2", "TEST_FILE_PATH_3")
            )
    }

    fun testSendingLlamaMessage() {
        useLlamaService()
        val configurationState = service<ConfigurationSettings>().state
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"
        configurationState.maxTokens = 1000
        configurationState.temperature = 0.1f
        val llamaSettings = LlamaSettings.getCurrentState()
        llamaSettings.isUseCustomModel = false
        llamaSettings.huggingFaceModel = HuggingFaceModel.CODE_LLAMA_7B_Q4
        llamaSettings.topK = 30
        llamaSettings.topP = 0.8
        llamaSettings.minP = 0.03
        llamaSettings.repeatPenalty = 1.3
        val message = Message("TEST_PROMPT")
        val conversation = ConversationService.getInstance().startConversation(project)
        val panel = ChatToolWindowTabPanel(project, conversation)
        expectLlama(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/completion")
            assertThat(request.body)
                .extracting(
                    "prompt",
                    "n_predict",
                    "stream",
                    "temperature",
                    "top_k",
                    "top_p",
                    "min_p",
                    "repeat_penalty"
                )
                .containsExactly(
                    LLAMA.buildPrompt(
                        "TEST_SYSTEM_PROMPT",
                        "TEST_PROMPT",
                        conversation.messages
                    ),
                    configurationState.maxTokens,
                    true,
                    configurationState.temperature.toDouble(),
                    llamaSettings.topK,
                    llamaSettings.topP,
                    llamaSettings.minP,
                    llamaSettings.repeatPenalty
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

        panel.sendMessage(message, ConversationType.DEFAULT)

        waitExpecting {
            val messages = conversation.messages
            messages.isNotEmpty() && "Hello!" == messages[0].response
                    && panel.tokenDetails.conversationTokens > 0
        }
        assertThat(panel.conversation)
            .isNotNull()
            .extracting("id", "discardTokenLimit")
            .containsExactly(conversation.id, false)
        val messages = panel.conversation.messages
        assertThat(messages).hasSize(1)
        assertThat(messages[0])
            .extracting("id", "prompt", "response")
            .containsExactly(message.id, message.prompt, message.response)
    }
}
