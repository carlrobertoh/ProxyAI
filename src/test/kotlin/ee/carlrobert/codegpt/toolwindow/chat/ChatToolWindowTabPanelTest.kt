package ee.carlrobert.codegpt.toolwindow.chat

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightVirtualFile
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.completions.ConversationType
import ee.carlrobert.codegpt.conversations.ConversationAttachedFile
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.ToolWindowInitialState
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import testsupport.http.RequestEntity
import testsupport.http.exchange.StreamHttpExchange
import testsupport.json.JSONUtil.e
import testsupport.json.JSONUtil.jsonArray
import testsupport.json.JSONUtil.jsonMap
import testsupport.json.JSONUtil.jsonMapResponse
import java.io.File
import java.util.*

class ChatToolWindowTabPanelTest : IntegrationTest() {

    override fun setUp() {
        super.setUp()
        useOpenAIService()
        service<ModelSettings>().setModel(FeatureType.CHAT, "gpt-5-mini", ServiceType.OPENAI)
    }

    fun testSendingMessagePersistsResponseAndTokenDetails() {
        service<PromptsSettings>().state.personas.selectedPersona.instructions =
            "TEST_SYSTEM_PROMPT"

        val message = Message("Hello!")
        val conversation = ConversationService.getInstance().startConversation(project)
        val panel = ChatToolWindowTabPanel(project, ToolWindowInitialState(conversation))
        expectOpenAIStreamingHello { promptText ->
            assertThat(promptText).contains("TEST_SYSTEM_PROMPT")
            assertThat(promptText).contains("Hello!")
        }

        panel.sendMessage(message, ConversationType.DEFAULT)

        waitExpecting {
            conversation.messages.isNotEmpty() && "Hello!" == conversation.messages[0].response
        }

        assertThat(panel.conversation.messages).hasSize(1)
        assertThat(panel.conversation.messages[0].response).isEqualTo("Hello!")
    }

    fun testSendingMessageWithReferencedFilesAddsFileContextToPrompt() {
        val message = Message("Explain referenced files")
        val conversation = ConversationService.getInstance().startConversation(project)
        val panel = ChatToolWindowTabPanel(project, ToolWindowInitialState(conversation))
        panel.includeFiles(
            listOf(
                LightVirtualFile("A.kt", "fun a() = 1"),
                LightVirtualFile("B.kt", "class B")
            )
        )
        expectOpenAIStreamingHello { promptText ->
            assertThat(promptText).contains("A.kt")
            assertThat(promptText).contains("fun a() = 1")
            assertThat(promptText).contains("B.kt")
            assertThat(promptText).contains("class B")
        }

        panel.sendMessage(message, ConversationType.DEFAULT)

        waitExpecting {
            conversation.messages.isNotEmpty() && "Hello!" == conversation.messages[0].response
        }
    }

    fun testFixCompileErrorsConversationUsesCoreActionPrompt() {
        service<PromptsSettings>().state.coreActions.fixCompileErrors.instructions =
            "FIX_ERRORS_SYSTEM_PROMPT"

        val message = Message("Fix compile errors in this class")
        val conversation = ConversationService.getInstance().startConversation(project)
        val panel = ChatToolWindowTabPanel(project, ToolWindowInitialState(conversation))
        expectOpenAIStreamingHello { promptText ->
            assertThat(promptText).contains("FIX_ERRORS_SYSTEM_PROMPT")
            assertThat(promptText).contains("Fix compile errors in this class")
        }

        panel.sendMessage(message, ConversationType.FIX_COMPILE_ERRORS)

        waitExpecting {
            conversation.messages.isNotEmpty() && "Hello!" == conversation.messages[0].response
        }
    }

    fun testSendingMessageWithImageStillBuildsPromptAndResponse() {
        val testImagePath =
            Objects.requireNonNull(javaClass.getResource("/images/test-image.png")).path
        project.putUserData(CodeGPTKeys.IMAGE_ATTACHMENT_FILE_PATH, testImagePath)

        val message = Message("What is in this image?")
        val conversation = ConversationService.getInstance().startConversation(project)
        val panel = ChatToolWindowTabPanel(project, ToolWindowInitialState(conversation))
        expectOpenAIStreamingHello { promptText ->
            assertThat(promptText).contains("What is in this image?")
        }

        panel.sendMessage(message, ConversationType.DEFAULT)

        waitExpecting {
            conversation.messages.isNotEmpty() && "Hello!" == conversation.messages[0].response
        }
    }

    fun testIncludingFilesPersistsConversationAttachedFiles() {
        val tempFile = File.createTempFile("PersistedAttachment", ".kt").apply {
            writeText("class $nameWithoutExtension")
            deleteOnExit()
        }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempFile)
        val conversation = ConversationService.getInstance().startConversation(project)
        val panel = ChatToolWindowTabPanel(project, ToolWindowInitialState(conversation))

        panel.includeFiles(listOf(virtualFile))

        waitExpecting {
            conversation.attachedFiles == listOf(ConversationAttachedFile(tempFile.path, true))
        }
    }

    private fun expectOpenAIStreamingHello(assertPrompt: (String) -> Unit) {
        expectOpenAI(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/responses")
            assertThat(request.method).isEqualTo("POST")
            assertPrompt(extractPromptText(request))
            openAiResponsesChunks("Hello!")
        })
    }

    private fun streamingChunk(content: String, sequenceNumber: Int): String {
        return jsonMapResponse(
            e("type", "response.output_text.delta"),
            e("item_id", "msg_1"),
            e("output_index", 0),
            e("content_index", 0),
            e("delta", content),
            e("sequence_number", sequenceNumber)
        )
    }

    private fun openAiResponsesChunks(text: String): List<String> {
        val chunks = text.chunked(3)
        return chunks.mapIndexed { index, chunk ->
            streamingChunk(chunk, index + 1)
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
                    e("id", "resp-test"),
                    e("object", "response"),
                    e("created_at", 1),
                    e("model", "gpt-5-mini"),
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

    private fun extractPromptText(request: RequestEntity): String {
        val messages = request.body["messages"] as? List<*>
        if (messages != null) {
            return messages.joinToString("\n") { message ->
                when (val content = (message as? Map<*, *>)?.get("content")) {
                    is String -> content
                    is List<*> -> content.joinToString("\n") { part ->
                        val partMap = part as? Map<*, *>
                        (partMap?.get("text") as? String) ?: partMap.toString()
                    }

                    null -> ""
                    else -> content.toString()
                }
            }
        }

        val input = request.body["input"] as? List<*> ?: return ""
        return input.joinToString("\n") { item ->
            when (val content = (item as? Map<*, *>)?.get("content")) {
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
}
