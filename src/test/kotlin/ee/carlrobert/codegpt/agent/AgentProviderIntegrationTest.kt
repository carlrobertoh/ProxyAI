package ee.carlrobert.codegpt.agent

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.serialization.JSONObject
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.agent.history.AgentCheckpointHistoryService
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.*
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.agent.clients.shouldStreamCustomOpenAI
import ee.carlrobert.codegpt.settings.models.ModelCatalog
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.toolwindow.agent.AgentSession
import ee.carlrobert.codegpt.toolwindow.agent.AgentToolWindowContentManager
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.time.Clock

class AgentProviderIntegrationTest : IntegrationTest() {

    fun testSubmitMessageContinuesArchivedThreadThroughRealRuntime() {
        configureCustomOpenAIService()
        val agentId = "archived-agent-${UUID.randomUUID()}"
        val resumeCheckpointId = "checkpoint-1"
        val finishCheckpointId = "checkpoint-2"
        val checkpointStorage =
            JVMFilePersistenceStorageProvider(Path(project.basePath ?: "", ".proxyai"))
        runBlocking {
            checkpointStorage.saveCheckpoint(
                agentId,
                AgentCheckpointData(
                    checkpointId = resumeCheckpointId,
                    createdAt = Clock.System.now(),
                    nodePath = "$agentId/single_run/nodeExecuteTool",
                    lastOutput = JSONObject(emptyMap()),
                    messageHistory = listOf(
                        Message.User("My name is Carl", RequestMetaInfo.Empty),
                        Message.Assistant("ok", ResponseMetaInfo.Empty),
                    ),
                    version = 0,
                )
            )
            checkpointStorage.saveCheckpoint(
                agentId,
                AgentCheckpointData(
                    checkpointId = finishCheckpointId,
                    createdAt = Clock.System.now(),
                    nodePath = "$agentId/single_run/__finish__",
                    lastOutput = JSONObject(emptyMap()),
                    messageHistory = listOf(
                        Message.User("My name is Carl", RequestMetaInfo.Empty),
                        Message.Assistant("ok", ResponseMetaInfo.Empty),
                    ),
                    version = 1,
                )
            )
        }

        val contentManager = project.service<AgentToolWindowContentManager>()
        val sessionId = "session-${UUID.randomUUID()}"
        contentManager.createNewAgentTab(
            AgentSession(
                sessionId = sessionId,
                conversation = Conversation(),
                agentId = agentId,
                resumeCheckpointRef = CheckpointRef(agentId, finishCheckpointId)
            ),
            select = false
        )
        val events = RecordingAgentEvents()

        try {
            expectCustomOpenAI(BasicHttpExchange { request ->
                val prompt = extractPromptText(request)
                assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
                assertThat(request.method).isEqualTo("POST")
                assertThat(prompt).contains("My name is Carl")
                assertThat(prompt).contains("What's my name?")
                ResponseEntity(customOpenAiResponse("Your name is Carl"))
            })

            project.service<AgentService>()
                .submitMessage(MessageWithContext("What's my name?"), events, sessionId)
            awaitSessionToFinish(sessionId)

            val latestCheckpoint = runBlocking {
                project.service<AgentCheckpointHistoryService>().loadLatestResumeCheckpoint(agentId)
            }

            assertThat(events.text.toString()).isEqualTo("Your name is Carl")
            assertThat(latestCheckpoint).isNotNull
            assertThat(latestCheckpoint!!.messageHistory.filterIsInstance<Message.User>().map { it.content })
                .contains("My name is Carl", "What's my name?")
        } finally {
            contentManager.removeSession(sessionId)
        }
    }

    fun testSubmitMessageUsesSeededSessionHistoryThroughRealRuntime() {
        configureCustomOpenAIService()
        val contentManager = project.service<AgentToolWindowContentManager>()
        val sessionId = "session-${UUID.randomUUID()}"
        contentManager.createNewAgentTab(
            AgentSession(
                sessionId = sessionId,
                conversation = Conversation(),
                seededMessageHistory = listOf(
                    Message.User("My name is Carl", RequestMetaInfo.Empty),
                    Message.Assistant("ok", ResponseMetaInfo.Empty)
                )
            ),
            select = false
        )
        val events = RecordingAgentEvents()

        try {
            expectCustomOpenAI(BasicHttpExchange { request ->
                val prompt = extractPromptText(request)
                assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
                assertThat(request.method).isEqualTo("POST")
                assertThat(prompt).contains("My name is Carl")
                assertThat(prompt).contains("What's my name?")
                ResponseEntity(customOpenAiResponse("Your name is Carl"))
            })

            project.service<AgentService>()
                .submitMessage(MessageWithContext("What's my name?"), events, sessionId)
            awaitSessionToFinish(sessionId)

            val session = requireNotNull(contentManager.getSession(sessionId))
            val runtimeAgentId = requireNotNull(session.agentId)
            val latestCheckpoint = runBlocking {
                project.service<AgentCheckpointHistoryService>().loadLatestResumeCheckpoint(runtimeAgentId)
            }

            assertThat(events.text.toString()).isEqualTo("Your name is Carl")
            assertThat(session.seededMessageHistory).isNull()
            assertThat(latestCheckpoint).isNotNull
            assertThat(latestCheckpoint!!.messageHistory.filterIsInstance<Message.User>().map { it.content })
                .contains("My name is Carl", "What's my name?")
        } finally {
            contentManager.removeSession(sessionId)
        }
    }

    fun testSecondFollowUpContinuesSeededSessionThroughRealRuntime() {
        configureCustomOpenAIService()
        val contentManager = project.service<AgentToolWindowContentManager>()
        val sessionId = "session-${UUID.randomUUID()}"
        contentManager.createNewAgentTab(
            AgentSession(
                sessionId = sessionId,
                conversation = Conversation(),
                seededMessageHistory = listOf(
                    Message.User("My name is Carl", RequestMetaInfo.Empty),
                    Message.Assistant("ok", ResponseMetaInfo.Empty)
                )
            ),
            select = false
        )
        val events = RecordingAgentEvents()

        try {
            expectCustomOpenAI(BasicHttpExchange { request ->
                val prompt = extractPromptText(request)
                assertThat(prompt).contains("My name is Carl")
                assertThat(prompt).contains("What's my name?")
                ResponseEntity(customOpenAiResponse("Your name is Carl"))
            })
            project.service<AgentService>()
                .submitMessage(MessageWithContext("What's my name?"), events, sessionId)
            awaitSessionToFinish(sessionId)

            expectCustomOpenAI(BasicHttpExchange { request ->
                val prompt = extractPromptText(request)
                assertThat(prompt).contains("My name is Carl")
                assertThat(prompt).contains("What's my name?")
                assertThat(prompt).contains("And what did I ask you?")
                ResponseEntity(customOpenAiResponse("You asked what your name was"))
            })
            project.service<AgentService>()
                .submitMessage(MessageWithContext("And what did I ask you?"), events, sessionId)
            awaitSessionToFinish(sessionId)

            assertThat(events.text.toString()).contains("Your name is Carl")
            assertThat(events.text.toString()).contains("You asked what your name was")
        } finally {
            contentManager.removeSession(sessionId)
        }
    }

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
        service<ModelSettings>().setModel(
            FeatureType.AGENT,
            ModelCatalog.MERCURY2,
            ServiceType.INCEPTION
        )
        expectInception(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractPromptText(request)).contains("Say hello from Inception")
            chatCompletionChunks(ModelCatalog.MERCURY2, "Hello from Inception")
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

    fun testCustomOpenAIAgentAllowsMissingApiKey() {
        val customService = configureCustomOpenAIService(apiKey = null)
        expectCustomOpenAI(BasicHttpExchange { request ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractPromptText(request)).contains("Say hello without Custom OpenAI auth")
            ResponseEntity(customOpenAiResponse("Hello without Custom OpenAI auth"))
        })

        val result = runAgent(ServiceType.CUSTOM_OPENAI, "Say hello without Custom OpenAI auth")

        assertThat(customService.id).isNotBlank()
        assertThat(result.output).isEqualTo("Hello without Custom OpenAI auth")
        assertThat(result.events.text.toString()).isEqualTo("Hello without Custom OpenAI auth")
    }

    fun testCustomOpenAIAgentStreamsWhenStoredSelectionUsesModelId() {
        val customService = configureCustomOpenAIService(stream = true)
        service<ModelSettings>().setModel(
            FeatureType.AGENT,
            "custom-agent-model",
            ServiceType.CUSTOM_OPENAI
        )
        assertThat(shouldStreamCustomOpenAI(FeatureType.AGENT)).isTrue()
        expectCustomOpenAI(StreamHttpExchange { request ->
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body["stream"]).isEqualTo(true)
            assertThat(extractPromptText(request)).contains("Say hello from streamed Custom OpenAI")
            chatCompletionChunks("custom-agent-model", "Hello from streamed Custom OpenAI")
        })

        val result = runAgent(ServiceType.CUSTOM_OPENAI, "Say hello from streamed Custom OpenAI")

        assertThat(customService.id).isNotBlank()
        assertThat(result.output).isEqualTo("Hello from streamed Custom OpenAI")
        assertThat(result.events.text.toString()).isEqualTo("Hello from streamed Custom OpenAI")
    }

    fun testCustomOpenAIResponsesAgentStreamsWhenStoredSelectionUsesModelId() {
        val customService = configureCustomOpenAIService(
            path = "/v1/responses",
            model = "custom-responses-model",
            stream = true,
            useResponsesApiBody = true
        )
        service<ModelSettings>().setModel(
            FeatureType.AGENT,
            "custom-responses-model",
            ServiceType.CUSTOM_OPENAI
        )
        assertThat(shouldStreamCustomOpenAI(FeatureType.AGENT)).isTrue()
        expectCustomOpenAI(StreamHttpExchange { request ->
            assertThat(request.uri.path).isEqualTo("/v1/responses")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body["stream"]).isEqualTo(true)
            assertThat(extractPromptText(request)).contains("Say hello from Custom OpenAI Responses")
            openAiResponsesChunks(
                model = "custom-responses-model",
                text = "Hello from Custom OpenAI Responses"
            )
        })

        val result = runAgent(ServiceType.CUSTOM_OPENAI, "Say hello from Custom OpenAI Responses")

        assertThat(customService.id).isNotBlank()
        assertThat(result.output).isEqualTo("Hello from Custom OpenAI Responses")
        assertThat(result.events.text.toString()).isEqualTo("Hello from Custom OpenAI Responses")
    }

    fun testCustomOpenAIResponsesAgentSerializesToolHistoryAsResponsesInput() {
        val fixture = createReadFixture("Custom Responses fixture")
        configureCustomOpenAIService(
            path = "/v1/responses",
            model = "custom-responses-model",
            stream = false,
            useResponsesApiBody = true
        )
        expectCustomOpenAI(BasicHttpExchange { request ->
            assertThat(request.uri.path).isEqualTo("/v1/responses")
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractPromptText(request)).contains("Read the fixture and repeat its contents")
            ResponseEntity(
                openAiResponsesToolCallResponse(
                    model = "custom-responses-model",
                    toolName = "Read",
                    callId = "call_custom_responses_read",
                    arguments = """{"file_path":"${fixture.path}"}"""
                )
            )
        })
        expectCustomOpenAI(BasicHttpExchange { request ->
            assertThat(request.uri.path).isEqualTo("/v1/responses")
            assertThat(request.method).isEqualTo("POST")
            assertThatResponsesToolHistory(
                request = request,
                toolName = "Read",
                callId = "call_custom_responses_read",
                fixture = fixture
            )
            ResponseEntity(
                openAiResponsesResponse(
                    model = "custom-responses-model",
                    text = "Custom Responses read: ${fixture.contents}"
                )
            )
        })

        val result = runAgent(ServiceType.CUSTOM_OPENAI, "Read the fixture and repeat its contents")

        assertThat(result.output).isEqualTo("Custom Responses read: ${fixture.contents}")
        assertThat(result.events.text.toString()).isEqualTo("Custom Responses read: ${fixture.contents}")
    }

    fun testCustomOpenAIResponsesStreamingAgentCompletesToolLoop() {
        val fixture = createReadFixture("Custom Responses streaming fixture")
        configureCustomOpenAIService(
            path = "/v1/responses",
            model = "custom-responses-model",
            stream = true,
            useResponsesApiBody = true
        )
        expectCustomOpenAI(StreamHttpExchange { request ->
            assertThat(request.uri.path).isEqualTo("/v1/responses")
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractPromptText(request)).contains("Read the fixture and repeat its contents")
            openAiResponsesToolCallChunks(
                model = "custom-responses-model",
                toolName = "Read",
                callId = "call_custom_responses_stream_read",
                arguments = """{"file_path":"${fixture.path}"}"""
            )
        })
        expectCustomOpenAI(StreamHttpExchange { request ->
            assertThat(request.uri.path).isEqualTo("/v1/responses")
            assertThat(request.method).isEqualTo("POST")
            assertThatResponsesToolHistory(
                request = request,
                toolName = "Read",
                callId = "call_custom_responses_stream_read",
                fixture = fixture
            )
            openAiResponsesChunks(
                model = "custom-responses-model",
                text = "Custom Responses streaming read: ${fixture.contents}"
            )
        })

        val result = runAgent(ServiceType.CUSTOM_OPENAI, "Read the fixture and repeat its contents")

        assertThat(result.output).isEqualTo("Custom Responses streaming read: ${fixture.contents}")
        assertThat(result.events.text.toString()).isEqualTo("Custom Responses streaming read: ${fixture.contents}")
    }

    fun testCustomOpenAIContinuationAfterCancelledToolCallDoesNotSendDanglingToolHistory() {
        configureCustomOpenAIService()
        val agentId = "cancelled-tool-agent-${UUID.randomUUID()}"
        val checkpointId = "checkpoint-${UUID.randomUUID()}"
        val checkpointStorage =
            JVMFilePersistenceStorageProvider(Path(project.basePath ?: "", ".proxyai"))
        runBlocking {
            checkpointStorage.saveCheckpoint(
                agentId,
                AgentCheckpointData(
                    checkpointId = checkpointId,
                    createdAt = Clock.System.now(),
                    nodePath = "$agentId/single_run/nodeExecuteTool",
                    lastOutput = JSONObject(emptyMap()),
                    messageHistory = listOf(
                        Message.User("Investigate with a subagent", RequestMetaInfo.Empty),
                        Message.Tool.Call(
                            id = "call_cancelled_task",
                            tool = "Task",
                            content = """{"subagent_type":"Explore","description":"Inspect code","prompt":"Find the bug"}""",
                            metaInfo = ResponseMetaInfo.Empty
                        )
                    ),
                    version = 0
                )
            )
        }

        val contentManager = project.service<AgentToolWindowContentManager>()
        val sessionId = "session-${UUID.randomUUID()}"
        contentManager.createNewAgentTab(
            AgentSession(
                sessionId = sessionId,
                conversation = Conversation(),
                agentId = agentId,
                resumeCheckpointRef = CheckpointRef(agentId, checkpointId)
            ),
            select = false
        )
        val observedRequest = AtomicReference<RequestEntity>()
        val events = RecordingAgentEvents()

        try {
            expectCustomOpenAI(BasicHttpExchange { request ->
                observedRequest.set(request)
                ResponseEntity(customOpenAiResponse("ok"))
            })

            project.service<AgentService>()
                .submitMessage(MessageWithContext("Continue after cancellation"), events, sessionId)
            awaitSessionToFinish(sessionId)

            val request = requireNotNull(observedRequest.get()) {
                "Expected a Custom OpenAI request"
            }
            assertThat(request.uri.path).isEqualTo("/v1/chat/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(extractPromptText(request)).contains(
                "Investigate with a subagent",
                "Continue after cancellation"
            )
            assertThat(hasDanglingToolCallBeforeUser(request))
                .describedAs("continuation request must not include an unanswered tool call before the new user message")
                .isFalse()
        } finally {
            contentManager.removeSession(sessionId)
        }
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
            pendingMessages = ConcurrentHashMap(),
            pendingRunContinuations = ConcurrentHashMap()
        )

        var agentId: String? = null
        return try {
            val output = runBlocking {
                val agent = service.createAgent()
                agentId = agent.id
                agent.run(MessageWithContext(userMessage))
            }
            AgentRunResult(output, events)
        } finally {
            agentId?.let { id ->
                runBlocking { service.removeAgentWithId(id) }
            }
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

    private fun openAiResponsesChunks(
        model: String = "gpt-4.1-mini",
        text: String = "Hello from OpenAI"
    ): List<String> {
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

    private fun openAiResponsesToolCallChunks(
        model: String,
        toolName: String,
        callId: String,
        arguments: String
    ): List<String> {
        return listOf(
            jsonMapResponse(
                e("type", "response.output_item.added"),
                e(
                    "item",
                    jsonMap(
                        e("type", "function_call"),
                        e("id", callId),
                        e("call_id", callId),
                        e("name", toolName),
                        e("arguments", "")
                    )
                ),
                e("output_index", 0),
                e("sequence_number", 1)
            ),
            jsonMapResponse(
                e("type", "response.function_call_arguments.delta"),
                e("item_id", callId),
                e("output_index", 0),
                e("delta", arguments),
                e("call_id", callId),
                e("sequence_number", 2)
            ),
            jsonMapResponse(
                e("type", "response.output_item.done"),
                e(
                    "item",
                    jsonMap(
                        e("type", "function_call"),
                        e("id", callId),
                        e("call_id", callId),
                        e("name", toolName),
                        e("arguments", arguments),
                        e("status", "completed")
                    )
                ),
                e("output_index", 0),
                e("sequence_number", 3)
            ),
            jsonMapResponse(
                e("type", "response.completed"),
                e(
                    "response",
                    jsonMap(
                        e("id", "resp-tool-call"),
                        e("object", "response"),
                        e("created_at", 1),
                        e("model", model),
                        e(
                            "output",
                            jsonArray(
                                jsonMap(
                                    e("type", "function_call"),
                                    e("id", callId),
                                    e("call_id", callId),
                                    e("name", toolName),
                                    e("arguments", arguments),
                                    e("status", "completed")
                                )
                            )
                        ),
                        e("parallel_tool_calls", true),
                        e("status", "completed"),
                        e("text", jsonMap())
                    )
                ),
                e("sequence_number", 4)
            )
        )
    }

    private fun openAiResponsesToolCallResponse(
        model: String,
        toolName: String,
        callId: String,
        arguments: String
    ): String {
        return jsonMapResponse(
            e("id", "resp-tool-call"),
            e("object", "response"),
            e("created_at", 1),
            e("model", model),
            e(
                "output",
                jsonArray(
                    jsonMap(
                        e("type", "function_call"),
                        e("id", "fc_1"),
                        e("call_id", callId),
                        e("name", toolName),
                        e("arguments", arguments),
                        e("status", "completed")
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

    private fun customOpenAiResponse(text: String = "Hello from Custom OpenAI"): String {
        val model = "custom-agent-model"
        return jsonMapResponse(
            e(
                "choices",
                jsonArray(
                    jsonMap(
                        e("finishReason", "stop"),
                        e("finish_reason", "stop"),
                        e("index", 0),
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

    private fun configureCustomOpenAIService(
        path: String = "/v1/chat/completions",
        model: String = "custom-agent-model",
        stream: Boolean = false,
        useResponsesApiBody: Boolean = false,
        apiKey: String? = "TEST_API_KEY"
    ): CustomServiceSettingsState {
        val settings = service<CustomServicesSettings>()
        val serviceState = CustomServiceSettingsState().apply {
            name = "Agent Test Custom Service"
            chatCompletionSettings.url =
                System.getProperty("customOpenAI.baseUrl") + path
            chatCompletionSettings.headers.clear()
            chatCompletionSettings.body.clear()
            chatCompletionSettings.body["model"] = model
            chatCompletionSettings.body["stream"] = stream
            if (useResponsesApiBody) {
                chatCompletionSettings.body["input"] = "\$OPENAI_MESSAGES"
            }
        }
        settings.state.services.clear()
        settings.state.services.add(serviceState)
        setCredential(
            CustomServiceApiKeyById(requireNotNull(serviceState.id)),
            apiKey
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

    private fun assertThatResponsesToolHistory(
        request: RequestEntity,
        toolName: String,
        callId: String,
        fixture: ReadFixture
    ) {
        val input = request.body["input"] as? List<*> ?: error("Expected responses input list")
        val items = input.mapNotNull { it as? Map<*, *> }

        assertThat(items.any { item ->
            item["type"] == "function_call" &&
                item["name"] == toolName &&
                item["call_id"] == callId &&
                item["arguments"] == """{"file_path":"${fixture.path}"}"""
        }).isTrue()
        assertThat(items.any { item ->
            item["type"] == "function_call_output" &&
                item["call_id"] == callId &&
                (item["output"] as? String).orEmpty().contains(fixture.contents)
        }).isTrue()
    }

    private fun hasDanglingToolCallBeforeUser(request: RequestEntity): Boolean {
        val messages = request.body["messages"] as? List<*> ?: return false
        val pendingToolCallIds = LinkedHashSet<String>()
        messages.mapNotNull { it as? Map<*, *> }.forEach { message ->
            when (message["role"]) {
                "assistant" -> {
                    if (pendingToolCallIds.isNotEmpty()) {
                        return true
                    }
                    val toolCalls = message["tool_calls"] as? List<*> ?: emptyList<Any>()
                    toolCalls
                        .mapNotNull { it as? Map<*, *> }
                        .mapNotNullTo(pendingToolCallIds) { it["id"] as? String }
                }

                "tool" -> {
                    val toolCallId = message["tool_call_id"] as? String
                    if (!toolCallId.isNullOrBlank()) {
                        pendingToolCallIds.remove(toolCallId)
                    }
                }

                "user" -> {
                    if (pendingToolCallIds.isNotEmpty()) {
                        return true
                    }
                }
            }
        }
        return pendingToolCallIds.isNotEmpty()
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

    private fun awaitSessionToFinish(sessionId: String, timeoutMillis: Long = 5_000L) {
        val agentService = project.service<AgentService>()
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (agentService.isSessionRunning(sessionId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertThat(agentService.isSessionRunning(sessionId)).isFalse
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

        override fun onQueuedMessagesResolved(message: MessageWithContext?) = Unit
    }
}
