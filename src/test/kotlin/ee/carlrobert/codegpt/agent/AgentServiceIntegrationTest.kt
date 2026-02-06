package ee.carlrobert.codegpt.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.AgentSession
import ee.carlrobert.codegpt.toolwindow.agent.AgentToolWindowContentManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.McpTagDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple.tuple
import testsupport.IntegrationTest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path

class AgentServiceIntegrationTest : IntegrationTest() {

    private lateinit var agentService: AgentService
    private lateinit var contentManager: AgentToolWindowContentManager
    private lateinit var originalRuntimeFactory: AgentRuntimeFactory
    private val createdSessions = mutableListOf<String>()
    private val noopEvents = object : AgentEvents {
        override fun onQueuedMessagesResolved() = Unit
    }
    private val runTimeoutMillis = 5_000L

    override fun setUp() {
        super.setUp()
        agentService = project.service()
        contentManager = project.service()
        originalRuntimeFactory = agentService.runtimeFactory
    }

    override fun tearDown() {
        agentService.runtimeFactory = originalRuntimeFactory
        createdSessions.forEach { sessionId ->
            runCatching { contentManager.removeSession(sessionId) }
        }
        createdSessions.clear()
        super.tearDown()
    }

    fun testSubmitMessageReusesRuntimeAndUpdatesResumeCheckpoint() {
        val sessionId = createSession("agent-runtime-reuse")
        val factory = RecordingRuntimeFactory(agentModel())
        agentService.runtimeFactory = factory
        val firstMessage = MessageWithContext("First request")
        val secondMessage = MessageWithContext("Second request")

        submitAndAwait(sessionId, firstMessage)
        val firstSnapshot = snapshot(sessionId)
        submitAndAwait(sessionId, secondMessage)
        val secondSnapshot = snapshot(sessionId)

        val runtimeAgentId = firstSnapshot.runtimeAgentId
        val managedService = factory.createdServices.single()
        assertThat(runtimeAgentId).isNotBlank()
        assertThat(factory.createdServices).hasSize(1)
        assertThat(listOf(firstSnapshot, secondSnapshot))
            .extracting("runtimeAgentId", "resumeCheckpointRef.agentId")
            .containsExactly(
                tuple(runtimeAgentId, runtimeAgentId),
                tuple(runtimeAgentId, runtimeAgentId)
            )
        assertThat(firstSnapshot.resumeCheckpointRef?.checkpointId).isNotBlank()
        assertThat(secondSnapshot.resumeCheckpointRef?.checkpointId).isNotBlank()
        assertThat(secondSnapshot.resumeCheckpointRef?.checkpointId)
            .isNotEqualTo(firstSnapshot.resumeCheckpointRef?.checkpointId)
        assertThat(managedService.createdAgentIds).hasSize(2)
        assertThat(managedService.createdAgentIds.last()).isEqualTo(runtimeAgentId)
    }

    fun testSubmitMessageRebuildsRuntimeWhenMcpSelectionChanges() {
        val sessionId = createSession("agent-runtime-mcp")
        val factory = RecordingRuntimeFactory(agentModel())
        agentService.runtimeFactory = factory
        val withoutMcp = MessageWithContext("Without MCP")
        val mcpTag = McpTagDetails(serverId = "server-1", serverName = "Server 1")
        val withMcp = MessageWithContext("With MCP", tags = listOf(mcpTag))

        submitAndAwait(sessionId, withoutMcp)
        submitAndAwait(sessionId, withMcp)

        assertThat(factory.createdServices).hasSize(2)
        assertThat(factory.createdServices)
            .extracting("closeAllCalls")
            .containsExactly(1, 0)
    }

    fun testGetCheckpointFallsBackToRuntimeAgentIdWhenResumeRefIsStale() {
        val sessionId = createSession("agent-checkpoint-fallback")
        val runtimeAgentId = "runtime-agent-${UUID.randomUUID()}"
        val checkpointId = "checkpoint-${UUID.randomUUID()}"
        val checkpointStorage = JVMFilePersistenceStorageProvider(Path(project.basePath ?: "", ".proxyai"))
        val staleRef = CheckpointRef(agentId = "missing-agent", checkpointId = "missing-checkpoint")
        runBlocking {
            checkpointStorage.saveCheckpoint(
                runtimeAgentId,
                AgentCheckpointData(
                    checkpointId = checkpointId,
                    createdAt = Clock.System.now(),
                    nodePath = "$runtimeAgentId/single_run/nodeExecuteTool",
                    lastInput = JsonNull,
                    messageHistory = listOf(
                        Message.User("hello", RequestMetaInfo.Empty),
                        Message.Assistant("world", ResponseMetaInfo.Empty),
                    ),
                    version = 0,
                )
            )
        }
        contentManager.setRuntimeAgentId(sessionId, runtimeAgentId)
        contentManager.setResumeCheckpointRef(sessionId, staleRef)

        val checkpoint = runBlocking { agentService.getCheckpoint(sessionId) }

        assertThat(checkpoint).isNotNull
        assertThat(checkpoint!!.checkpointId).isEqualTo(checkpointId)
        assertThat(contentManager.getSession(sessionId)!!.resumeCheckpointRef)
            .isEqualTo(CheckpointRef(runtimeAgentId, checkpointId))
    }

    private fun submitAndAwait(
        sessionId: String,
        message: MessageWithContext
    ) {
        agentService.submitMessage(message, noopEvents, sessionId)
        awaitSessionToFinish(sessionId)
    }

    private fun awaitSessionToFinish(sessionId: String) {
        val deadline = System.currentTimeMillis() + runTimeoutMillis
        while (agentService.isSessionRunning(sessionId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertThat(agentService.isSessionRunning(sessionId)).isFalse
    }

    private fun createSession(prefix: String): String {
        val sessionId = "$prefix-${UUID.randomUUID()}"
        contentManager.createNewAgentTab(
            AgentSession(
                sessionId = sessionId,
                conversation = Conversation()
            ),
            select = false
        )
        createdSessions.add(sessionId)
        return sessionId
    }

    private fun snapshot(sessionId: String): SessionSnapshot {
        val session = requireNotNull(contentManager.getSession(sessionId))
        return SessionSnapshot(
            runtimeAgentId = session.runtimeAgentId,
            resumeCheckpointRef = session.resumeCheckpointRef
        )
    }

    private fun agentModel(): LLModel {
        return project.service<ModelSelectionService>().getAgentModel()
    }

    private data class SessionSnapshot(
        val runtimeAgentId: String?,
        val resumeCheckpointRef: CheckpointRef?
    )
}

private class RecordingRuntimeFactory(
    private val model: LLModel
) : AgentRuntimeFactory {

    val createdServices = mutableListOf<FakeManagedAgentService>()

    override fun create(
        project: Project,
        checkpointStorage: JVMFilePersistenceStorageProvider,
        provider: ServiceType,
        events: AgentEvents,
        sessionId: String,
        pendingMessages: ConcurrentHashMap<String, ArrayDeque<MessageWithContext>>
    ): AIAgentService<MessageWithContext, String, out AIAgent<MessageWithContext, String>> {
        val service = FakeManagedAgentService(checkpointStorage, model)
        createdServices.add(service)
        return service
    }
}

private class FakeManagedAgentService(
    private val checkpointStorage: JVMFilePersistenceStorageProvider,
    model: LLModel
) : AIAgentService<MessageWithContext, String, FakeManagedAgent> {

    override val promptExecutor: PromptExecutor = NoopPromptExecutor
    override val agentConfig: AIAgentConfig = AIAgentConfig(
        prompt = prompt("fake-service") {},
        model = model,
        maxAgentIterations = 1
    )
    override val toolRegistry: ToolRegistry = ToolRegistry.EMPTY

    private val agents = linkedMapOf<String, FakeManagedAgent>()
    val createdAgentIds = mutableListOf<String>()
    var closeAllCalls: Int = 0

    override suspend fun createAgent(
        id: String?,
        additionalToolRegistry: ToolRegistry,
        agentConfig: AIAgentConfig,
        clock: Clock
    ): FakeManagedAgent {
        val actualId = id ?: UUID.randomUUID().toString()
        val agent = FakeManagedAgent(actualId, agentConfig, checkpointStorage)
        agents[actualId] = agent
        createdAgentIds.add(actualId)
        return agent
    }

    override suspend fun removeAgent(agent: FakeManagedAgent): Boolean {
        return removeAgentWithId(agent.id)
    }

    override suspend fun removeAgentWithId(id: String): Boolean {
        return agents.remove(id) != null
    }

    override suspend fun agentById(id: String): FakeManagedAgent? = agents[id]

    override suspend fun listAllAgents(): List<FakeManagedAgent> = agents.values.toList()

    override suspend fun listActiveAgents(): List<FakeManagedAgent> = emptyList()

    override suspend fun listInactiveAgents(): List<FakeManagedAgent> = agents.values.toList()

    override suspend fun listFinishedAgents(): List<FakeManagedAgent> = agents.values.toList()

    override suspend fun closeAll() {
        closeAllCalls += 1
        super.closeAll()
    }
}

private class FakeManagedAgent(
    override val id: String,
    override val agentConfig: AIAgentConfig,
    private val checkpointStorage: JVMFilePersistenceStorageProvider
) : AIAgent<MessageWithContext, String> {

    private var state: AIAgent.Companion.State<String> = AIAgent.Companion.State.NotStarted()

    override suspend fun getState(): AIAgent.Companion.State<String> = state

    override suspend fun run(agentInput: MessageWithContext): String {
        state = AIAgent.Companion.State.Starting()

        val latest = checkpointStorage.getLatestCheckpoint(id)
        val checkpoint = AgentCheckpointData(
            checkpointId = UUID.randomUUID().toString(),
            createdAt = Clock.System.now(),
            nodePath = "$id/single_run/nodeExecuteTool",
            lastInput = JsonNull,
            messageHistory = listOf(
                Message.User(agentInput.text, RequestMetaInfo.Empty),
                Message.Assistant("ok", ResponseMetaInfo.Empty)
            ),
            version = (latest?.version ?: -1) + 1
        )
        checkpointStorage.saveCheckpoint(id, checkpoint)

        state = AIAgent.Companion.State.Finished("ok")
        return "ok"
    }

    override suspend fun close() = Unit
}

private object NoopPromptExecutor : PromptExecutor {
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>
    ): List<Message.Response> {
        throw UnsupportedOperationException("NoopPromptExecutor should not be used in tests")
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>
    ): Flow<StreamFrame> {
        throw UnsupportedOperationException("NoopPromptExecutor should not be used in tests")
    }

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel
    ): ai.koog.prompt.dsl.ModerationResult {
        throw UnsupportedOperationException("NoopPromptExecutor should not be used in tests")
    }

    override fun close() = Unit
}
