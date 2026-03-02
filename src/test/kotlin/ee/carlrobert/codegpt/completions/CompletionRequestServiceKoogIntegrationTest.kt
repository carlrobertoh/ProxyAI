package ee.carlrobert.codegpt.completions

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
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

class CompletionServiceKoogIntegrationTest : IntegrationTest() {

    fun testLookupCompletionUsesKoogPromptExecution() {
        useOpenAIService("gpt-4o", FeatureType.LOOKUP)
        useStableOpenAIModel(FeatureType.LOOKUP)
        expectOpenAISyncResponse("lookup_1,lookup_2") { promptText ->
            assertThat(promptText).contains("generate names for InvoiceService")
        }

        val result = service<CompletionService>()
            .getLookupCompletion(LookupCompletionParameters("generate names for InvoiceService"))

        assertThat(result).isEqualTo("lookup_1,lookup_2")
    }

    fun testCommitMessageFlowUsesKoogStreamingExecution() {
        useOpenAIService("gpt-4o", FeatureType.COMMIT_MESSAGE)
        useStableOpenAIModel(FeatureType.COMMIT_MESSAGE)
        expectOpenAIStreamingHello { promptText ->
            assertThat(promptText).contains("Generate a concise commit message")
            assertThat(promptText).contains("diff --git a/A.kt b/A.kt")
        }

        val listener = RecordingListener()

        service<CompletionService>().getCommitMessage(
            CommitMessageCompletionParameters(
                gitDiff = "diff --git a/A.kt b/A.kt",
                systemPrompt = "Generate a concise commit message"
            ),
            listener
        )
        waitExpecting { listener.completed == "Hello!" }

        assertThat(listener.opened).isTrue()
        assertThat(listener.error).isNull()
        assertThat(listener.chunks).containsExactly("Hel", "lo", "!")
    }

    fun testInlineEditFlowUsesKoogStreamingExecution() {
        useOpenAIService("gpt-4o", FeatureType.INLINE_EDIT)
        useStableOpenAIModel(FeatureType.INLINE_EDIT)
        expectOpenAIStreamingHello { promptText ->
            assertThat(promptText).contains("Rename this function")
            assertThat(promptText).contains("Sure, renaming now")
            assertThat(promptText).contains("Implement.")
        }

        val conversation = ConversationService.getInstance().startConversation(project)
        conversation.addMessage(Message("Rename this function", "Sure, renaming now"))

        val listener = RecordingListener()

        service<CompletionService>().getInlineEditCompletion(
            InlineEditCompletionParameters(
                selectedText = "fun oldName() = 1",
                fileExtension = "kt",
                projectBasePath = project.basePath,
                conversation = conversation
            ),
            listener
        )
        waitExpecting { listener.completed == "Hello!" }

        assertThat(listener.opened).isTrue()
        assertThat(listener.error).isNull()
        assertThat(listener.chunks).containsExactly("Hel", "lo", "!")
    }

    fun testAutoApplyFlowUsesKoogStreamingExecution() {
        useOpenAIService("gpt-4o", FeatureType.AUTO_APPLY)
        useStableOpenAIModel(FeatureType.AUTO_APPLY)
        expectOpenAIStreamingHello { promptText ->
            assertThat(promptText).contains("fun value() = 1")
            assertThat(promptText).contains("fun value() = 2")
        }

        val destination = myFixture.configureByText(
            "Destination.kt",
            "fun value() = 1"
        ).virtualFile
        val listener = RecordingListener()

        service<CompletionService>().autoApply(
            AutoApplyParameters(
                source = "fun value() = 2",
                destination = destination
            ),
            listener
        )
        waitExpecting { listener.completed == "Hello!" }

        assertThat(listener.opened).isTrue()
        assertThat(listener.error).isNull()
        assertThat(listener.chunks).containsExactly("Hel", "lo", "!")
    }

    fun testAutoApplySyncFlowUsesKoogExecution() {
        useOpenAIService("gpt-4o", FeatureType.AUTO_APPLY)
        useStableOpenAIModel(FeatureType.AUTO_APPLY)
        expectOpenAISyncResponse("fun value() = 2") { promptText ->
            assertThat(promptText).contains("fun value() = 1")
            assertThat(promptText).contains("fun value() = 2")
        }

        val destination = myFixture.configureByText(
            "SyncDestination.kt",
            "fun value() = 1"
        ).virtualFile

        val result = service<CompletionService>().autoApply(
            AutoApplyParameters(
                source = "fun value() = 2",
                destination = destination
            )
        )

        assertThat(result).isEqualTo("fun value() = 2")
    }

    private class RecordingListener : CompletionStreamEventListener {
        val chunks = mutableListOf<String>()
        var completed: String? = null
        var opened: Boolean = false
        var error: Throwable? = null

        override fun onOpen() {
            opened = true
        }

        override fun onMessage(message: String) {
            if (message.isNotEmpty()) {
                chunks.add(message)
            }
        }

        override fun onComplete(messageBuilder: StringBuilder) {
            completed = messageBuilder.toString()
        }

        override fun onError(error: CompletionError, ex: Throwable) {
            this.error = ex
        }

        override fun onCancelled(messageBuilder: StringBuilder) {
            completed = messageBuilder.toString()
        }
    }

    private fun expectOpenAIStreamingHello(assertPrompt: (String) -> Unit) {
        expectOpenAI(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/responses")
            assertThat(request.method).isEqualTo("POST")
            assertPrompt(extractPromptText(request))
            listOf(
                streamingChunk("Hel", 1),
                streamingChunk("lo", 2),
                streamingChunk("!", 3)
            )
        })
    }

    private fun expectOpenAISyncResponse(
        responseText: String,
        assertPrompt: (String) -> Unit
    ) {
        expectOpenAI(BasicHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/responses")
            assertThat(request.method).isEqualTo("POST")
            assertPrompt(extractPromptText(request))
            ResponseEntity(
                jsonMapResponse(
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
                                            e("text", responseText),
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
            )
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

    private fun extractPromptText(request: RequestEntity): String {
        val messages = request.body["messages"] as? List<*>
        if (messages != null) {
            return messages.joinToString("\n") { message ->
                val content = (message as? Map<*, *>)?.get("content")
                when (content) {
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
            val content = (item as? Map<*, *>)?.get("content")
            when (content) {
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

    private fun useStableOpenAIModel(featureType: FeatureType) {
        service<ModelSettings>().setModel(featureType, "gpt-5-mini", ServiceType.OPENAI)
    }
}
