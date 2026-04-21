package ee.carlrobert.codegpt.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.AIAgentContext
import ai.koog.agents.core.agent.session.AIAgentRunSession
import ai.koog.agents.core.feature.pipeline.AIAgentPipeline
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.isTombstone
import ai.koog.agents.snapshot.feature.tombstoneCheckpoint
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.JSONObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.agent.history.AgentHistoryThreadSummary
import ee.carlrobert.codegpt.agent.history.AgentCheckpointHistoryService
import ee.carlrobert.codegpt.agent.history.ThreadStatus
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.AgentSession
import ee.carlrobert.codegpt.toolwindow.agent.AgentToolWindowContentManager
import ee.carlrobert.codegpt.toolwindow.ui.ResponseMessagePanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.McpTagDetails
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple.tuple
import testsupport.IntegrationTest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.time.Clock

class AgentServiceIntegrationTest : IntegrationTest() {

    private lateinit var agentService: AgentService
    private lateinit var contentManager: AgentToolWindowContentManager
    private lateinit var originalRuntimeFactory: AgentRuntimeFactory
    private val createdSessions = mutableListOf<String>()
    private val noopEvents = object : AgentEvents {
        override fun onQueuedMessagesResolved(message: MessageWithContext?) = Unit
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
        val firstActualSnapshot = snapshot(sessionId)
        submitAndAwait(sessionId, secondMessage)
        val secondActualSnapshot = snapshot(sessionId)

        val actualRuntimeAgentId = firstActualSnapshot.runtimeAgentId
        val actualManagedService = factory.createdServices.single()
        assertThat(actualRuntimeAgentId).isNotBlank()
        assertThat(factory.createdServices).hasSize(1)
        assertThat(listOf(firstActualSnapshot, secondActualSnapshot))
            .extracting("runtimeAgentId", "resumeCheckpointRef.agentId")
            .containsExactly(
                tuple(actualRuntimeAgentId, actualRuntimeAgentId),
                tuple(actualRuntimeAgentId, actualRuntimeAgentId)
            )
        assertThat(firstActualSnapshot.resumeCheckpointRef?.checkpointId).isNotBlank()
        assertThat(secondActualSnapshot.resumeCheckpointRef?.checkpointId).isNotBlank()
        assertThat(secondActualSnapshot.resumeCheckpointRef?.checkpointId)
            .isNotEqualTo(firstActualSnapshot.resumeCheckpointRef?.checkpointId)
        assertThat(actualManagedService.createdAgentIds).hasSize(2)
        assertThat(actualManagedService.createdAgentIds.last()).isEqualTo(actualRuntimeAgentId)
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

        val actualCreatedServices = factory.createdServices
        assertThat(actualCreatedServices).hasSize(2)
    }

    fun testSubmitMessageEmitsRunCheckpointCallback() {
        val sessionId = createSession("agent-runtime-callback")
        val factory = RecordingRuntimeFactory(agentModel())
        agentService.runtimeFactory = factory
        val message = MessageWithContext("Callback request")
        var callbackMessageId: UUID? = null
        var callbackRef: CheckpointRef? = null
        val events = object : AgentEvents {
            override fun onQueuedMessagesResolved(message: MessageWithContext?) = Unit

            override fun onRunCheckpointUpdated(runMessageId: UUID, ref: CheckpointRef?) {
                callbackMessageId = runMessageId
                callbackRef = ref
            }
        }

        agentService.submitMessage(message, events, sessionId)
        awaitSessionToFinish(sessionId)

        val actualSession = contentManager.getSession(sessionId)
        assertThat(callbackMessageId).isEqualTo(message.id)
        assertThat(callbackRef).isNotNull
        assertThat(callbackRef?.agentId).isEqualTo(actualSession?.agentId)
    }

    fun testSubmitMessageContinuesThreadWithoutResumeCheckpointRef() {
        val sessionId = createSession("agent-runtime-without-resume-ref")
        val factory = RecordingRuntimeFactory(agentModel())
        agentService.runtimeFactory = factory
        val firstMessage = MessageWithContext("First request")
        val secondMessage = MessageWithContext("Second request")

        submitAndAwait(sessionId, firstMessage)
        val firstSnapshot = snapshot(sessionId)
        contentManager.setResumeCheckpointRef(sessionId, null)

        submitAndAwait(sessionId, secondMessage)

        assertThat(snapshot(sessionId).runtimeAgentId).isEqualTo(firstSnapshot.runtimeAgentId)
        assertThat(factory.createdServices.single().historySeenByInput["Second request"])
            .contains("First request")
    }

    fun testSubmitMessageUsesSeededSessionHistoryBeforeFirstCheckpoint() {
        val sessionId = "agent-runtime-seeded-${UUID.randomUUID()}"
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
        createdSessions.add(sessionId)

        val factory = RecordingRuntimeFactory(agentModel())
        agentService.runtimeFactory = factory

        submitAndAwait(sessionId, MessageWithContext("What's my name?"))

        val session = requireNotNull(contentManager.getSession(sessionId))
        val runtimeAgentId = requireNotNull(session.agentId)
        val latestCheckpoint = runBlocking {
            project.service<AgentCheckpointHistoryService>().loadLatestResumeCheckpoint(runtimeAgentId)
        }

        assertThat(session.resumeCheckpointRef?.agentId).isEqualTo(runtimeAgentId)
        assertThat(session.seededMessageHistory).isNull()
        assertThat(factory.createdServices.single().historySeenByInput["What's my name?"])
            .contains("My name is Carl")
        assertThat(latestCheckpoint).isNotNull
        assertThat(latestCheckpoint!!.messageHistory.filterIsInstance<Message.User>().map { it.content })
            .contains("My name is Carl", "What's my name?")
    }

    fun testOpenCheckpointConversationContinuesOriginalThread() {
        val agentId = "archived-agent-${UUID.randomUUID()}"
        val firstCheckpointId = "checkpoint-1"
        val finishCheckpointId = "checkpoint-2"
        val checkpointStorage = JVMFilePersistenceStorageProvider(Path(project.basePath ?: "", ".proxyai"))
        runBlocking {
            checkpointStorage.saveCheckpoint(
                agentId,
                AgentCheckpointData(
                    checkpointId = firstCheckpointId,
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

        val thread = AgentHistoryThreadSummary(
            agentId = agentId,
            latest = CheckpointRef(agentId, finishCheckpointId),
            latestCreatedAt = Clock.System.now(),
            status = ThreadStatus.COMPLETED,
            runCount = 2,
            title = "Recovered conversation",
            preview = "ok"
        )
        val panel = contentManager.openCheckpointConversation(thread, Conversation(), thread.latest)
        val sessionId = panel.getAgentSession().sessionId
        createdSessions.add(sessionId)

        val factory = TombstoneRuntimeFactory(agentModel())
        agentService.runtimeFactory = factory

        submitAndAwait(sessionId, MessageWithContext("What's my name?"))

        val latestCheckpoint = runBlocking {
            project.service<AgentCheckpointHistoryService>().loadLatestResumeCheckpoint(agentId)
        }
        assertThat(snapshot(sessionId).runtimeAgentId).isEqualTo(agentId)
        assertThat(factory.service.runIdsByInput["What's my name?"]).containsExactly(agentId)
        assertThat(factory.service.historySeenByInput["What's my name?"]).contains("My name is Carl")
        assertThat(latestCheckpoint).isNotNull
        assertThat(latestCheckpoint!!.messageHistory.filterIsInstance<Message.User>().map { it.content })
            .contains("My name is Carl", "What's my name?")
    }

    fun testOpenCheckpointConversationSelectsExistingTabForSameAgentId() {
        val agentId = "existing-agent-${UUID.randomUUID()}"
        val checkpointRef = CheckpointRef(agentId, "checkpoint-${UUID.randomUUID()}")
        val existingPanel = contentManager.createNewAgentTab(
            AgentSession(
                sessionId = "existing-session-${UUID.randomUUID()}",
                conversation = Conversation(),
                agentId = agentId,
                resumeCheckpointRef = checkpointRef
            ),
            select = false
        )
        createdSessions.add(existingPanel.getSessionId())

        val thread = AgentHistoryThreadSummary(
            agentId = agentId,
            latest = checkpointRef,
            latestCreatedAt = Clock.System.now(),
            status = ThreadStatus.PARTIAL,
            runCount = 1,
            title = "Recovered conversation",
            preview = "ok"
        )
        val initialTabCount = contentManager.getTabbedPane().tabCount

        val reopenedPanel = contentManager.openCheckpointConversation(
            thread,
            Conversation(),
            checkpointRef
        )

        assertThat(reopenedPanel).isSameAs(existingPanel)
        assertThat(contentManager.getTabbedPane().tabCount).isEqualTo(initialTabCount)
        assertThat(contentManager.getActiveTabPanel()).isSameAs(existingPanel)
    }

    fun testCloseOtherTabsExceptKeepsSelectedSessionAndRemovesOthers() {
        val keptSessionId = createSession("agent-close-others-keep")
        val closedSessionId1 = createSession("agent-close-others-drop-1")
        val closedSessionId2 = createSession("agent-close-others-drop-2")
        val tabbedPane = contentManager.getTabbedPane()

        tabbedPane.selectSession(closedSessionId1)
        tabbedPane.closeOtherTabsExcept(keptSessionId)

        assertThat(tabbedPane.tabCount).isEqualTo(1)
        assertThat(tabbedPane.tryFindTabTitle(keptSessionId)).isPresent
        assertThat(tabbedPane.tryFindActiveTabPanel().orElse(null)?.getSessionId()).isEqualTo(keptSessionId)
        assertThat(contentManager.getSession(keptSessionId)).isNotNull
        assertThat(contentManager.getSession(closedSessionId1)).isNull()
        assertThat(contentManager.getSession(closedSessionId2)).isNull()

        createdSessions.remove(closedSessionId1)
        createdSessions.remove(closedSessionId2)
    }

    fun testGetCheckpointFallsBackToRuntimeAgentIdWhenResumeRefIsStale() {
        val sessionId = createSession("agent-checkpoint-fallback")
        val runtimeAgentId = "runtime-agent-${UUID.randomUUID()}"
        val checkpointId = "checkpoint-${UUID.randomUUID()}"
        val checkpointStorage =
            JVMFilePersistenceStorageProvider(Path(project.basePath ?: "", ".proxyai"))
        val staleRef = CheckpointRef(agentId = "missing-agent", checkpointId = "missing-checkpoint")
        runBlocking {
            checkpointStorage.saveCheckpoint(
                runtimeAgentId,
                AgentCheckpointData(
                    checkpointId = checkpointId,
                    createdAt = Clock.System.now(),
                    nodePath = "$runtimeAgentId/single_run/nodeExecuteTool",
                    lastOutput = JSONObject(emptyMap()),
                    messageHistory = listOf(
                        Message.User("hello", RequestMetaInfo.Empty),
                        Message.Assistant("world", ResponseMetaInfo.Empty),
                    ),
                    version = 0,
                )
            )
        }
        contentManager.setAgentId(sessionId, runtimeAgentId)
        contentManager.setResumeCheckpointRef(sessionId, staleRef)

        val actualCheckpoint = runBlocking { agentService.getCheckpoint(sessionId) }
        val actualSession = contentManager.getSession(sessionId)
        assertThat(actualCheckpoint).isNotNull
        assertThat(actualCheckpoint!!.checkpointId).isEqualTo(checkpointId)
        assertThat(actualSession!!.resumeCheckpointRef)
            .isEqualTo(CheckpointRef(runtimeAgentId, checkpointId))
    }

    fun testCancelPreservesContextForFollowUpMessage() {
        val sessionId = createSession("agent-cancel-queue")
        val factory = ControlledCancellationRuntimeFactory(agentModel())
        agentService.runtimeFactory = factory
        val firstMessage = MessageWithContext("First request")
        val followUpMessage = MessageWithContext("Follow-up request")

        agentService.submitMessage(firstMessage, noopEvents, sessionId)
        runBlocking {
            factory.service.firstRunStarted.await()
        }

        agentService.cancelCurrentRun(sessionId)
        runBlocking {
            factory.service.firstRunCancellationEntered.await()
        }

        agentService.submitMessage(followUpMessage, noopEvents, sessionId)
        factory.service.allowFirstRunExit.complete(Unit)
        awaitCondition { factory.service.createdAgentIds.size == 2 }
        awaitSessionToFinish(sessionId)

        assertThat(factory.service.createdAgentIds).hasSize(2)
        assertThat(factory.service.historySeenByInput["Follow-up request"])
            .contains("First request")
    }

    fun testQueuedFollowUpRunsAfterFirstRunCompletes() {
        val sessionId = createSession("agent-natural-queue")
        val factory = DelayedCompletionRuntimeFactory(agentModel())
        agentService.runtimeFactory = factory
        val firstMessage = MessageWithContext("First request")
        val followUpMessage = MessageWithContext("Follow-up request")

        agentService.submitMessage(firstMessage, noopEvents, sessionId)
        runBlocking {
            factory.service.firstRunStarted.await()
        }

        agentService.submitMessage(followUpMessage, noopEvents, sessionId)
        runBlocking {
            factory.service.allowFirstRunFinish.complete(Unit)
        }

        awaitCondition { factory.service.createdAgentIds.size == 2 }
        awaitSessionToFinish(sessionId)

        assertThat(factory.service.createdAgentIds).hasSize(2)
        assertThat(factory.service.historySeenByInput["Follow-up request"])
            .contains("First request")
    }

    fun testQueuedFollowUpCreatesSeparateRunCardInUi() {
        val sessionId = "agent-queued-ui-${UUID.randomUUID()}"
        val panel = contentManager.createNewAgentTab(
            AgentSession(
                sessionId = sessionId,
                conversation = Conversation()
            ),
            select = false
        )
        createdSessions.add(sessionId)
        ApplicationManager.getApplication().invokeAndWait {
            val registerRunCard = panel.javaClass.getDeclaredMethod(
                "registerRunCard",
                UUID::class.java,
                String::class.java,
                ResponseMessagePanel::class.java,
                String::class.java
            ).apply {
                isAccessible = true
            }
            registerRunCard.invoke(
                panel,
                UUID.randomUUID(),
                "rollback-1",
                ResponseMessagePanel(),
                "First request"
            )

            panel.javaClass.getDeclaredMethod("markActiveRunCompleted").apply {
                isAccessible = true
                invoke(panel)
            }

            panel.javaClass.getDeclaredMethod(
                "promoteQueuedMessageToActiveRun",
                MessageWithContext::class.java
            ).apply {
                isAccessible = true
                invoke(panel, MessageWithContext("Follow-up request"))
            }
        }

        val runCardsField = panel.javaClass.getDeclaredField("runCardsByMessageId").apply {
            isAccessible = true
        }
        val runCards = runCardsField.get(panel) as Map<*, *>

        assertThat(runCards).hasSize(2)
    }

    fun testSubmitMessageContinuesThreadWhenLatestStoredCheckpointIsTombstone() {
        val sessionId = createSession("agent-runtime-tombstone")
        val factory = TombstoneRuntimeFactory(agentModel())
        agentService.runtimeFactory = factory
        val firstMessage = MessageWithContext("My name is Carl")
        val secondMessage = MessageWithContext("What's my name?")

        submitAndAwait(sessionId, firstMessage)
        val firstSnapshot = snapshot(sessionId)
        submitAndAwait(sessionId, secondMessage)
        val secondSnapshot = snapshot(sessionId)

        val actualRuntimeAgentId = requireNotNull(firstSnapshot.runtimeAgentId)
        assertThat(secondSnapshot.runtimeAgentId).isEqualTo(actualRuntimeAgentId)
        assertThat(firstSnapshot.resumeCheckpointRef?.agentId).isEqualTo(actualRuntimeAgentId)
        assertThat(secondSnapshot.resumeCheckpointRef?.agentId).isEqualTo(actualRuntimeAgentId)
        assertThat(factory.service.runIdsByInput["My name is Carl"])
            .containsExactly(actualRuntimeAgentId)
        assertThat(factory.service.runIdsByInput["What's my name?"])
            .containsExactly(actualRuntimeAgentId)
        assertThat(factory.service.historySeenByInput["What's my name?"])
            .contains("My name is Carl")
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

    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + runTimeoutMillis
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertThat(predicate()).isTrue
    }

    private fun createSession(prefix: String): String {
        val sessionId = "$prefix-${UUID.randomUUID()}"
        contentManager.createNewAgentTab(
            AgentSession(
                sessionId = sessionId,
                conversation = Conversation(),
                createdAt = System.currentTimeMillis(),
                lastActiveAt = System.currentTimeMillis()
            ),
            select = false
        )
        createdSessions.add(sessionId)
        return sessionId
    }

    private fun snapshot(sessionId: String): SessionSnapshot {
        val session = requireNotNull(contentManager.getSession(sessionId))
        return SessionSnapshot(
            runtimeAgentId = session.agentId,
            resumeCheckpointRef = session.resumeCheckpointRef
        )
    }

    private fun agentModel(): LLModel {
        return project.service<ModelSettings>().getAgentModel()
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
        pendingMessages: ConcurrentHashMap<String, ArrayDeque<MessageWithContext>>,
        pendingRunContinuations: ConcurrentHashMap<String, PendingRunContinuation>
    ): AIAgentService<MessageWithContext, String, out AIAgent<MessageWithContext, String>> {
        val service = FakeManagedAgentService(checkpointStorage, model, sessionId, pendingRunContinuations)
        createdServices.add(service)
        return service
    }
}

private class ControlledCancellationRuntimeFactory(
    private val model: LLModel
) : AgentRuntimeFactory {

    lateinit var service: ControlledCancellationManagedAgentService

    override fun create(
        project: Project,
        checkpointStorage: JVMFilePersistenceStorageProvider,
        provider: ServiceType,
        events: AgentEvents,
        sessionId: String,
        pendingMessages: ConcurrentHashMap<String, ArrayDeque<MessageWithContext>>,
        pendingRunContinuations: ConcurrentHashMap<String, PendingRunContinuation>
    ): AIAgentService<MessageWithContext, String, out AIAgent<MessageWithContext, String>> {
        val created = ControlledCancellationManagedAgentService(
            checkpointStorage,
            model,
            sessionId,
            pendingRunContinuations
        )
        service = created
        return created
    }
}

private class TombstoneRuntimeFactory(
    private val model: LLModel
) : AgentRuntimeFactory {

    lateinit var service: TombstoneManagedAgentService

    override fun create(
        project: Project,
        checkpointStorage: JVMFilePersistenceStorageProvider,
        provider: ServiceType,
        events: AgentEvents,
        sessionId: String,
        pendingMessages: ConcurrentHashMap<String, ArrayDeque<MessageWithContext>>,
        pendingRunContinuations: ConcurrentHashMap<String, PendingRunContinuation>
    ): AIAgentService<MessageWithContext, String, out AIAgent<MessageWithContext, String>> {
        val created = TombstoneManagedAgentService(
            checkpointStorage,
            model,
            sessionId,
            pendingRunContinuations
        )
        service = created
        return created
    }
}

private class DelayedCompletionRuntimeFactory(
    private val model: LLModel
) : AgentRuntimeFactory {

    lateinit var service: DelayedCompletionManagedAgentService

    override fun create(
        project: Project,
        checkpointStorage: JVMFilePersistenceStorageProvider,
        provider: ServiceType,
        events: AgentEvents,
        sessionId: String,
        pendingMessages: ConcurrentHashMap<String, ArrayDeque<MessageWithContext>>,
        pendingRunContinuations: ConcurrentHashMap<String, PendingRunContinuation>
    ): AIAgentService<MessageWithContext, String, out AIAgent<MessageWithContext, String>> {
        val created = DelayedCompletionManagedAgentService(
            checkpointStorage,
            model,
            sessionId,
            pendingRunContinuations
        )
        service = created
        return created
    }
}

private class FakeManagedAgentService(
    private val checkpointStorage: JVMFilePersistenceStorageProvider,
    model: LLModel,
    private val sessionId: String,
    private val pendingRunContinuations: ConcurrentHashMap<String, PendingRunContinuation>
) : AIAgentService<MessageWithContext, String, FakeManagedAgent>() {

    override val promptExecutor: PromptExecutor = NoopPromptExecutor
    override val agentConfig: AIAgentConfig = AIAgentConfig(
        prompt = prompt("fake-service") {},
        model = model,
        maxAgentIterations = 1
    )
    override val toolRegistry: ToolRegistry = ToolRegistry.EMPTY

    private val agents = linkedMapOf<String, FakeManagedAgent>()
    val createdAgentIds = mutableListOf<String>()
    val historySeenByInput = linkedMapOf<String, List<String>>()

    override suspend fun createAgent(
        id: String?,
        additionalToolRegistry: ToolRegistry,
        agentConfig: AIAgentConfig,
        clock: Clock
    ): FakeManagedAgent {
        val actualId = id ?: UUID.randomUUID().toString()
        val agent = FakeManagedAgent(actualId, agentConfig, this, checkpointStorage)
        agents[actualId] = agent
        createdAgentIds.add(actualId)
        return agent
    }

    override suspend fun createAgentAndRun(
        agentInput: MessageWithContext,
        id: String?,
        additionalToolRegistry: ToolRegistry,
        agentConfig: AIAgentConfig,
        clock: Clock
    ): String {
        return createAgent(id, additionalToolRegistry, agentConfig, clock).run(agentInput)
    }

    override suspend fun removeAgent(agent: FakeManagedAgent): Boolean {
        return removeAgentWithId(agent.id)
    }

    override suspend fun removeAgentWithId(id: String): Boolean {
        return agents.remove(id) != null
    }

    override suspend fun agentById(id: String): FakeManagedAgent? = agents[id]

    fun consumeContinuation(): PendingRunContinuation? = pendingRunContinuations.remove(sessionId)
}

private class ControlledCancellationManagedAgentService(
    private val checkpointStorage: JVMFilePersistenceStorageProvider,
    model: LLModel,
    private val sessionId: String,
    private val pendingRunContinuations: ConcurrentHashMap<String, PendingRunContinuation>
) : AIAgentService<MessageWithContext, String, ControlledCancellationManagedAgent>() {

    override val promptExecutor: PromptExecutor = NoopPromptExecutor
    override val agentConfig: AIAgentConfig = AIAgentConfig(
        prompt = prompt("controlled-cancel-service") {},
        model = model,
        maxAgentIterations = 1
    )
    override val toolRegistry: ToolRegistry = ToolRegistry.EMPTY

    private val agents = linkedMapOf<String, ControlledCancellationManagedAgent>()
    val createdAgentIds = mutableListOf<String>()
    val historySeenByInput = linkedMapOf<String, List<String>>()
    val firstRunStarted = CompletableDeferred<Unit>()
    val firstRunCancellationEntered = CompletableDeferred<Unit>()
    val allowFirstRunExit = CompletableDeferred<Unit>()
    private var runCount = 0

    override suspend fun createAgent(
        id: String?,
        additionalToolRegistry: ToolRegistry,
        agentConfig: AIAgentConfig,
        clock: Clock
    ): ControlledCancellationManagedAgent {
        val actualId = id ?: UUID.randomUUID().toString()
        val agent =
            ControlledCancellationManagedAgent(actualId, agentConfig, this, checkpointStorage)
        agents[actualId] = agent
        createdAgentIds.add(actualId)
        return agent
    }

    override suspend fun createAgentAndRun(
        agentInput: MessageWithContext,
        id: String?,
        additionalToolRegistry: ToolRegistry,
        agentConfig: AIAgentConfig,
        clock: Clock
    ): String {
        return createAgent(id, additionalToolRegistry, agentConfig, clock).run(agentInput)
    }

    override suspend fun removeAgent(agent: ControlledCancellationManagedAgent): Boolean {
        return removeAgentWithId(agent.id)
    }

    override suspend fun removeAgentWithId(id: String): Boolean {
        return agents.remove(id) != null
    }

    override suspend fun agentById(id: String): ControlledCancellationManagedAgent? = agents[id]

    fun nextRunIndex(): Int {
        runCount += 1
        return runCount
    }

    fun consumeContinuation(): PendingRunContinuation? = pendingRunContinuations.remove(sessionId)
}

private class TombstoneManagedAgentService(
    private val checkpointStorage: JVMFilePersistenceStorageProvider,
    model: LLModel,
    private val sessionId: String,
    private val pendingRunContinuations: ConcurrentHashMap<String, PendingRunContinuation>
) : AIAgentService<MessageWithContext, String, TombstoneManagedAgent>() {

    override val promptExecutor: PromptExecutor = NoopPromptExecutor
    override val agentConfig: AIAgentConfig = AIAgentConfig(
        prompt = prompt("tombstone-service") {},
        model = model,
        maxAgentIterations = 1
    )
    override val toolRegistry: ToolRegistry = ToolRegistry.EMPTY

    private val agents = linkedMapOf<String, TombstoneManagedAgent>()
    val historySeenByInput = linkedMapOf<String, List<String>>()
    val runIdsByInput = linkedMapOf<String, List<String>>()

    override suspend fun createAgent(
        id: String?,
        additionalToolRegistry: ToolRegistry,
        agentConfig: AIAgentConfig,
        clock: Clock
    ): TombstoneManagedAgent {
        val actualId = id ?: UUID.randomUUID().toString()
        val agent = TombstoneManagedAgent(actualId, agentConfig, this, checkpointStorage)
        agents[actualId] = agent
        return agent
    }

    override suspend fun createAgentAndRun(
        agentInput: MessageWithContext,
        id: String?,
        additionalToolRegistry: ToolRegistry,
        agentConfig: AIAgentConfig,
        clock: Clock
    ): String {
        return createAgent(id, additionalToolRegistry, agentConfig, clock).run(agentInput)
    }

    override suspend fun removeAgent(agent: TombstoneManagedAgent): Boolean {
        return removeAgentWithId(agent.id)
    }

    override suspend fun removeAgentWithId(id: String): Boolean {
        return agents.remove(id) != null
    }

    override suspend fun agentById(id: String): TombstoneManagedAgent? = agents[id]

    fun consumeContinuation(): PendingRunContinuation? = pendingRunContinuations.remove(sessionId)
}

private class DelayedCompletionManagedAgentService(
    private val checkpointStorage: JVMFilePersistenceStorageProvider,
    model: LLModel,
    private val sessionId: String,
    private val pendingRunContinuations: ConcurrentHashMap<String, PendingRunContinuation>
) : AIAgentService<MessageWithContext, String, DelayedCompletionManagedAgent>() {

    override val promptExecutor: PromptExecutor = NoopPromptExecutor
    override val agentConfig: AIAgentConfig = AIAgentConfig(
        prompt = prompt("delayed-completion-service") {},
        model = model,
        maxAgentIterations = 1
    )
    override val toolRegistry: ToolRegistry = ToolRegistry.EMPTY

    private val agents = linkedMapOf<String, DelayedCompletionManagedAgent>()
    val createdAgentIds = mutableListOf<String>()
    val historySeenByInput = linkedMapOf<String, List<String>>()
    val firstRunStarted = CompletableDeferred<Unit>()
    val allowFirstRunFinish = CompletableDeferred<Unit>()
    private var runCount = 0

    override suspend fun createAgent(
        id: String?,
        additionalToolRegistry: ToolRegistry,
        agentConfig: AIAgentConfig,
        clock: Clock
    ): DelayedCompletionManagedAgent {
        val actualId = id ?: UUID.randomUUID().toString()
        val agent = DelayedCompletionManagedAgent(actualId, agentConfig, this, checkpointStorage)
        agents[actualId] = agent
        createdAgentIds.add(actualId)
        return agent
    }

    override suspend fun createAgentAndRun(
        agentInput: MessageWithContext,
        id: String?,
        additionalToolRegistry: ToolRegistry,
        agentConfig: AIAgentConfig,
        clock: Clock
    ): String {
        return createAgent(id, additionalToolRegistry, agentConfig, clock).run(agentInput)
    }

    override suspend fun removeAgent(agent: DelayedCompletionManagedAgent): Boolean {
        return removeAgentWithId(agent.id)
    }

    override suspend fun removeAgentWithId(id: String): Boolean {
        return agents.remove(id) != null
    }

    override suspend fun agentById(id: String): DelayedCompletionManagedAgent? = agents[id]

    fun nextRunIndex(): Int {
        runCount += 1
        return runCount
    }

    fun consumeContinuation(): PendingRunContinuation? = pendingRunContinuations.remove(sessionId)
}

private class ControlledCancellationManagedAgent(
    override val id: String,
    override val agentConfig: AIAgentConfig,
    private val owner: ControlledCancellationManagedAgentService,
    private val checkpointStorage: JVMFilePersistenceStorageProvider
) : AIAgent<MessageWithContext, String>() {

    override suspend fun run(agentInput: MessageWithContext, sessionId: String?): String {
        val runIndex = owner.nextRunIndex()
        val continuation = owner.consumeContinuation()
        val latest = checkpointStorage.getLatestCheckpoint(id)
        val history = continuation?.messageHistory ?: latest?.messageHistory.orEmpty()
        owner.historySeenByInput[agentInput.text] = history
            .filterIsInstance<Message.User>()
            .map { it.content }

        val updatedHistory = buildList {
            addAll(history)
            add(Message.User(agentInput.text, RequestMetaInfo.Empty))
            add(Message.Assistant("ok", ResponseMetaInfo.Empty))
        }
        checkpointStorage.saveCheckpoint(
            id,
            AgentCheckpointData(
                checkpointId = UUID.randomUUID().toString(),
                createdAt = Clock.System.now(),
                nodePath = "$id/single_run/nodeExecuteTool",
                lastOutput = JSONObject(emptyMap()),
                messageHistory = updatedHistory,
                version = (latest?.version ?: -1) + 1
            )
        )

        if (runIndex == 1) {
            owner.firstRunStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                owner.firstRunCancellationEntered.complete(Unit)
                withContext(NonCancellable) {
                    owner.allowFirstRunExit.await()
                }
            }
        }

        return "ok"
    }

    override fun createSession(sessionId: String?): AIAgentRunSession<MessageWithContext, String, out AIAgentContext> {
        return object : AIAgentRunSession<MessageWithContext, String, AIAgentContext> {
            override suspend fun run(input: MessageWithContext): String {
                return this@ControlledCancellationManagedAgent.run(input, sessionId)
            }

            override fun pipeline(): AIAgentPipeline {
                throw UnsupportedOperationException("ControlledCancellationManagedAgent session pipeline is not used in tests")
            }

            override fun context(): AIAgentContext {
                throw UnsupportedOperationException("ControlledCancellationManagedAgent session context is not used in tests")
            }
        }
    }

    override suspend fun close() = Unit
}

private class TombstoneManagedAgent(
    override val id: String,
    override val agentConfig: AIAgentConfig,
    private val owner: TombstoneManagedAgentService,
    private val checkpointStorage: JVMFilePersistenceStorageProvider
) : AIAgent<MessageWithContext, String>() {

    override suspend fun run(agentInput: MessageWithContext, sessionId: String?): String {
        val runId = sessionId ?: UUID.randomUUID().toString()
        owner.runIdsByInput[agentInput.text] = listOf(runId)

        val latest = checkpointStorage.getLatestCheckpoint(runId)
            ?.takeUnless { it.isTombstone() }
        val continuation = owner.consumeContinuation()
        val history = continuation?.messageHistory ?: latest?.messageHistory.orEmpty()
        owner.historySeenByInput[agentInput.text] = history
            .filterIsInstance<Message.User>()
            .map { it.content }

        val checkpoint = AgentCheckpointData(
            checkpointId = UUID.randomUUID().toString(),
            createdAt = Clock.System.now(),
            nodePath = "$runId/single_run/nodeExecuteTool",
            lastOutput = JSONObject(emptyMap()),
            messageHistory = buildList {
                addAll(history)
                add(Message.User(agentInput.text, RequestMetaInfo.Empty))
                add(Message.Assistant("ok", ResponseMetaInfo.Empty))
            },
            version = (latest?.version ?: -1) + 1
        )
        checkpointStorage.saveCheckpoint(runId, checkpoint)
        checkpointStorage.saveCheckpoint(
            runId,
            tombstoneCheckpoint(Clock.System.now(), checkpoint.version + 1)
        )

        return "ok"
    }

    override fun createSession(sessionId: String?): AIAgentRunSession<MessageWithContext, String, out AIAgentContext> {
        return object : AIAgentRunSession<MessageWithContext, String, AIAgentContext> {
            override suspend fun run(input: MessageWithContext): String {
                return this@TombstoneManagedAgent.run(input, sessionId)
            }

            override fun pipeline(): AIAgentPipeline {
                throw UnsupportedOperationException("TombstoneManagedAgent session pipeline is not used in tests")
            }

            override fun context(): AIAgentContext {
                throw UnsupportedOperationException("TombstoneManagedAgent session context is not used in tests")
            }
        }
    }

    override suspend fun close() = Unit
}

private class DelayedCompletionManagedAgent(
    override val id: String,
    override val agentConfig: AIAgentConfig,
    private val owner: DelayedCompletionManagedAgentService,
    private val checkpointStorage: JVMFilePersistenceStorageProvider
) : AIAgent<MessageWithContext, String>() {

    override suspend fun run(agentInput: MessageWithContext, sessionId: String?): String {
        val runIndex = owner.nextRunIndex()
        val continuation = owner.consumeContinuation()
        val latest = checkpointStorage.getLatestCheckpoint(id)
        val history = continuation?.messageHistory ?: latest?.messageHistory.orEmpty()
        owner.historySeenByInput[agentInput.text] = history
            .filterIsInstance<Message.User>()
            .map { it.content }

        val updatedHistory = buildList {
            addAll(history)
            add(Message.User(agentInput.text, RequestMetaInfo.Empty))
            add(Message.Assistant("ok", ResponseMetaInfo.Empty))
        }
        checkpointStorage.saveCheckpoint(
            id,
            AgentCheckpointData(
                checkpointId = UUID.randomUUID().toString(),
                createdAt = Clock.System.now(),
                nodePath = "$id/single_run/nodeExecuteTool",
                lastOutput = JSONObject(emptyMap()),
                messageHistory = updatedHistory,
                version = (latest?.version ?: -1) + 1
            )
        )

        if (runIndex == 1) {
            owner.firstRunStarted.complete(Unit)
            owner.allowFirstRunFinish.await()
        }

        return "ok"
    }

    override fun createSession(sessionId: String?): AIAgentRunSession<MessageWithContext, String, out AIAgentContext> {
        return object : AIAgentRunSession<MessageWithContext, String, AIAgentContext> {
            override suspend fun run(input: MessageWithContext): String {
                return this@DelayedCompletionManagedAgent.run(input, sessionId)
            }

            override fun pipeline(): AIAgentPipeline {
                throw UnsupportedOperationException("DelayedCompletionManagedAgent session pipeline is not used in tests")
            }

            override fun context(): AIAgentContext {
                throw UnsupportedOperationException("DelayedCompletionManagedAgent session context is not used in tests")
            }
        }
    }

    override suspend fun close() = Unit
}

private class FakeManagedAgent(
    override val id: String,
    override val agentConfig: AIAgentConfig,
    private val owner: FakeManagedAgentService,
    private val checkpointStorage: JVMFilePersistenceStorageProvider
) : AIAgent<MessageWithContext, String>() {

    override suspend fun run(agentInput: MessageWithContext, sessionId: String?): String {
        val latest = checkpointStorage.getLatestCheckpoint(id)
        val continuation = owner.consumeContinuation()
        val history = continuation?.messageHistory ?: latest?.messageHistory.orEmpty()
        owner.historySeenByInput[agentInput.text] = history
            .filterIsInstance<Message.User>()
            .map { it.content }
        val checkpoint = AgentCheckpointData(
            checkpointId = UUID.randomUUID().toString(),
            createdAt = Clock.System.now(),
            nodePath = "$id/single_run/nodeExecuteTool",
            lastOutput = JSONObject(emptyMap()),
            messageHistory = buildList {
                addAll(history)
                add(Message.User(agentInput.text, RequestMetaInfo.Empty))
                add(Message.Assistant("ok", ResponseMetaInfo.Empty))
            },
            version = (latest?.version ?: -1) + 1
        )
        checkpointStorage.saveCheckpoint(id, checkpoint)

        return "ok"
    }

    override fun createSession(sessionId: String?): AIAgentRunSession<MessageWithContext, String, out AIAgentContext> {
        return object : AIAgentRunSession<MessageWithContext, String, AIAgentContext> {
            override suspend fun run(input: MessageWithContext): String {
                return this@FakeManagedAgent.run(input, sessionId)
            }

            override fun pipeline(): AIAgentPipeline {
                throw UnsupportedOperationException("FakeManagedAgent session pipeline is not used in tests")
            }

            override fun context(): AIAgentContext {
                throw UnsupportedOperationException("FakeManagedAgent session context is not used in tests")
            }
        }
    }

    override suspend fun close() = Unit
}

private object NoopPromptExecutor : PromptExecutor() {
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): List<Message.Response> {
        throw UnsupportedOperationException("NoopPromptExecutor should not be used in tests")
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        throw UnsupportedOperationException("NoopPromptExecutor should not be used in tests")
    }

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel
    ): ModerationResult {
        throw UnsupportedOperationException("NoopPromptExecutor should not be used in tests")
    }

    override fun close() = Unit
}
