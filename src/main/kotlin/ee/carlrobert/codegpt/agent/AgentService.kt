package ee.carlrobert.codegpt.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.prompt.llm.LLMCapability
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgentService
import ee.carlrobert.codegpt.agent.history.AgentCheckpointHistoryService
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.AgentSession
import ee.carlrobert.codegpt.toolwindow.agent.AgentToolWindowContentManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.McpTagDetails
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonNull
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.time.Clock
import ai.koog.prompt.message.Message as PromptMessage

internal fun interface AgentRuntimeFactory {
    fun create(
        project: Project,
        checkpointStorage: JVMFilePersistenceStorageProvider,
        provider: ServiceType,
        events: AgentEvents,
        sessionId: String,
        pendingMessages: ConcurrentHashMap<String, ArrayDeque<MessageWithContext>>,
    ): AIAgentService<MessageWithContext, String, out AIAgent<MessageWithContext, String>>
}

@Service(Service.Level.PROJECT)
class AgentService(private val project: Project) {

    companion object {
        private val logger = thisLogger()
    }

    private data class SessionRuntime(
        val service: AIAgentService<MessageWithContext, String, out AIAgent<MessageWithContext, String>>,
        val provider: ServiceType,
        val modelSignature: ModelRuntimeSignature,
        val selectedServerIds: Set<String>,
        val events: AgentEvents
    )

    private data class ModelRuntimeSignature(
        val selectionId: String,
        val modelId: String,
        val contextLength: Long?,
        val maxOutputTokens: Long?,
        val usesResponsesApi: Boolean,
    )

    private val sessionJobs = ConcurrentHashMap<String, Job>()
    private val pendingMessages = ConcurrentHashMap<String, ArrayDeque<MessageWithContext>>()
    private val sessionAgents = ConcurrentHashMap<String, AIAgent<MessageWithContext, String>>()
    private val sessionRuntimes = ConcurrentHashMap<String, SessionRuntime>()
    internal var runtimeFactory: AgentRuntimeFactory =
        AgentRuntimeFactory { p, storage, provider, events, sid, pending ->
            ProxyAIAgent.createService(
                project = p,
                checkpointStorage = storage,
                provider = provider,
                events = events,
                sessionId = sid,
                pendingMessages = pending
            )
        }
    private val checkpointStorage =
        JVMFilePersistenceStorageProvider(Path(project.basePath ?: "", ".proxyai"))
    private val historyService = project.service<AgentCheckpointHistoryService>()

    private val _queuedMessageProcessed = MutableSharedFlow<String>()
    val queuedMessageProcessed = _queuedMessageProcessed.asSharedFlow()

    fun addToQueue(message: MessageWithContext, sessionId: String) {
        val queue = pendingMessages.getOrPut(sessionId) { ArrayDeque() }
        queue.add(message)
        logger.debug(
            "Queued agent message for session=$sessionId queueSize=${queue.size} uiVisible=${message.uiVisible} messageId=${message.id}"
        )
    }

    suspend fun getCheckpoint(sessionId: String): AgentCheckpointData? {
        val session =
            project.service<AgentToolWindowContentManager>().getSession(sessionId) ?: return null
        val ref = session.resumeCheckpointRef

        if (ref != null) {
            val checkpoint = runCatching { historyService.loadResumeCheckpoint(ref) }
                .onFailure { ex -> logCheckpointLoadFailure(sessionId, ref.agentId, ex) }
                .getOrNull()
            if (checkpoint != null) {
                return checkpoint
            }
        }

        val runtimeAgentId = session.runtimeAgentId ?: return null
        val latest = runCatching { historyService.loadLatestResumeCheckpoint(runtimeAgentId) }
            .onFailure { ex -> logCheckpointLoadFailure(sessionId, runtimeAgentId, ex) }
            .getOrNull()
            ?: return null

        project.service<AgentToolWindowContentManager>()
            .setResumeCheckpointRef(sessionId, CheckpointRef(runtimeAgentId, latest.checkpointId))
        return latest
    }

    fun submitMessage(message: MessageWithContext, events: AgentEvents, sessionId: String) {
        val selectedServerIds = updateMcpContext(sessionId, message)

        if (isSessionRunning(sessionId)) {
            addToQueue(message, sessionId)
            return
        }

        val provider = service<ModelSettings>().getServiceForFeature(FeatureType.AGENT)
        val contentManager = project.service<AgentToolWindowContentManager>()
        val session = contentManager.getSession(sessionId) ?: return
        if (!session.externalAgentId.isNullOrBlank()) {
            if (session.shouldRecreateExternalAgentSession(selectedServerIds)) {
                project.service<ExternalAcpAgentService>().closeSession(sessionId)
                session.externalAgentSessionId = null
                session.externalAgentMcpServerIds = emptySet()
            }
            submitExternalMessage(session, message, events, provider)
            return
        }

        val runtimeAgentId = contentManager.getSession(sessionId)?.runtimeAgentId
        val runtime = runCatching {
            ensureSessionRuntime(sessionId, provider, events)
        }.onFailure { ex ->
            logger.warn("Failed to initialize runtime for session=$sessionId", ex)
            events.onAgentException(provider, ex)
        }.getOrNull() ?: return

        val agent = runCatching {
            runBlocking {
                runtime.service.createAgent(id = runtimeAgentId)
            }
        }.onFailure { ex ->
            logger.warn("Failed to create managed agent for session=$sessionId", ex)
            events.onAgentException(provider, ex)
        }.getOrNull() ?: return

        contentManager.setRuntimeAgentId(sessionId, agent.id)
        sessionAgents[sessionId] = agent
        sessionJobs[sessionId] = CoroutineScope(Dispatchers.IO).launch {
            try {
                agent.run(message)
            } catch (_: CancellationException) {
                return@launch
            } catch (ex: Throwable) {
                logger.error(ex)
                events.onAgentException(provider, ex)
            } finally {
                val ref = refreshSessionResumeCheckpoint(sessionId, agent.id)
                events.onRunCheckpointUpdated(message.id, ref)
                sessionAgents.remove(sessionId, agent)
                runCatching { runtime.service.removeAgentWithId(agent.id) }
                    .onFailure { ex ->
                        logger.warn(
                            "Failed removing managed agent for session=$sessionId agentId=${agent.id}",
                            ex
                        )
                    }
                sessionJobs.remove(sessionId)
            }
        }
    }

    fun cancelCurrentRun(sessionId: String) {
        val session = project.service<AgentToolWindowContentManager>().getSession(sessionId)
        if (session?.externalAgentId != null) {
            runCatching {
                runBlocking {
                    project.service<ExternalAcpAgentService>()
                        .cancelSession(sessionId, session.externalAgentSessionId)
                }
            }.onFailure { ex ->
                logger.warn("Failed cancelling external ACP session for session=$sessionId", ex)
            }
        }
        sessionJobs[sessionId]?.cancel()
        sessionJobs.remove(sessionId)
    }

    fun removeSession(sessionId: String) {
        cancelCurrentRun(sessionId)
        pendingMessages.remove(sessionId)
        sessionAgents.remove(sessionId)
        sessionRuntimes.remove(sessionId)?.let { runtime ->
            runCatching {
                runBlocking { runtime.service.closeAll() }
            }.onFailure { ex ->
                logger.warn("Failed closing managed agent service for session=$sessionId", ex)
            }
        }
        project.service<ExternalAcpAgentService>().closeSession(sessionId)
        project.service<AgentToolWindowContentManager>()
            .getSession(sessionId)
            ?.also {
                it.externalAgentSessionId = null
                it.externalAgentMcpServerIds = emptySet()
            }
        project.service<AgentMcpContextService>().clear(sessionId)
    }

    fun isSessionRunning(sessionId: String): Boolean {
        return sessionJobs[sessionId]?.isActive == true
    }

    fun getPendingMessages(sessionId: String): List<MessageWithContext> {
        return pendingMessages[sessionId]?.toList() ?: emptyList()
    }

    fun clearPendingMessages(sessionId: String) {
        pendingMessages[sessionId]?.clear()
    }

    fun getAgentForSession(sessionId: String): AIAgent<MessageWithContext, String>? {
        return sessionAgents[sessionId]
    }

    private fun submitExternalMessage(
        session: AgentSession,
        message: MessageWithContext,
        events: AgentEvents,
        provider: ServiceType
    ) {
        logger.debug(
            "Starting external ACP run for session=${session.sessionId} externalAgent=${session.externalAgentId} messageId=${message.id}"
        )
        sessionJobs[session.sessionId] = CoroutineScope(Dispatchers.IO).launch {
            try {
                project.service<ExternalAcpAgentService>()
                    .runPromptLoop(
                        session = session,
                        firstMessage = message,
                        events = events,
                        pollNextQueued = {
                            val queue =
                                pendingMessages[session.sessionId] ?: return@runPromptLoop null
                            if (queue.isEmpty()) {
                                logger.debug("No queued ACP follow-up message for session=${session.sessionId}")
                                null
                            } else {
                                queue.removeFirst().also { next ->
                                    logger.debug(
                                        "Dequeued ACP follow-up message for session=${session.sessionId} queueRemaining=${queue.size} messageId=${next.id} uiVisible=${next.uiVisible}"
                                    )
                                }
                            }
                        }
                    )
            } catch (_: CancellationException) {
                return@launch
            } catch (ex: Throwable) {
                logger.error(ex)
                events.onAgentException(provider, ex)
            } finally {
                events.onRunCheckpointUpdated(message.id, null)
                sessionJobs.remove(session.sessionId)
            }
        }
    }

    private fun updateMcpContext(sessionId: String, message: MessageWithContext): Set<String> {
        val selectedServerIds = message.tags
            .filterIsInstance<McpTagDetails>()
            .filter { it.selected }
            .map { it.serverId }
            .toSet()

        val existingConversationId = project.service<AgentMcpContextService>()
            .get(sessionId)
            ?.conversationId
        val conversationId = project.service<AgentToolWindowContentManager>()
            .getSession(sessionId)
            ?.conversation
            ?.id
            ?: existingConversationId

        project.service<AgentMcpContextService>()
            .update(sessionId, conversationId, selectedServerIds)
        return selectedServerIds
    }

    suspend fun createSeedCheckpointFromHistory(history: List<PromptMessage>): CheckpointRef? =
        withContext(Dispatchers.IO) {
            if (history.isEmpty()) {
                return@withContext null
            }

            val agentId = UUID.randomUUID().toString()
            val checkpointId = UUID.randomUUID().toString()
            val checkpoint = AgentCheckpointData(
                checkpointId = checkpointId,
                createdAt = Clock.System.now(),
                nodePath = "$agentId/single_run/nodeExecuteTool",
                lastInput = JsonNull,
                messageHistory = history,
                version = 0
            )

            runCatching {
                checkpointStorage.saveCheckpoint(agentId, checkpoint)
                CheckpointRef(agentId, checkpointId)
            }.onFailure { ex ->
                logger.warn(
                    "Agent checkpoints: failed to create seed checkpoint from history " +
                            "agentId=$agentId error=${ex.message}",
                    ex
                )
            }.getOrNull()
        }

    private suspend fun refreshSessionResumeCheckpoint(
        sessionId: String,
        agentId: String
    ): CheckpointRef? {
        val ref = historyService.loadLatestResumeCheckpoint(agentId)
            ?.let { CheckpointRef(agentId, it.checkpointId) }
        if (ref != null) {
            project.service<AgentToolWindowContentManager>().setResumeCheckpointRef(sessionId, ref)
        }
        return ref
    }

    private fun ensureSessionRuntime(
        sessionId: String,
        provider: ServiceType,
        events: AgentEvents
    ): SessionRuntime {
        val modelSignature = currentModelSignature()
        val selectedServerIds = project.service<AgentMcpContextService>()
            .get(sessionId)
            ?.selectedServerIds ?: emptySet()
        val existing = sessionRuntimes[sessionId]
        if (existing != null &&
            existing.provider == provider &&
            existing.modelSignature == modelSignature &&
            existing.selectedServerIds == selectedServerIds &&
            existing.events === events
        ) {
            return existing
        }

        existing?.let { stale ->
            runCatching { runBlocking { stale.service.closeAll() } }
                .onFailure { ex ->
                    logger.warn("Failed closing stale managed service for session=$sessionId", ex)
                }
        }

        val created = SessionRuntime(
            service = runtimeFactory.create(
                project = project,
                checkpointStorage = checkpointStorage,
                provider = provider,
                events = events,
                sessionId = sessionId,
                pendingMessages = pendingMessages
            ),
            provider = provider,
            modelSignature = modelSignature,
            selectedServerIds = selectedServerIds,
            events = events
        )
        sessionRuntimes[sessionId] = created
        return created
    }

    private fun currentModelSignature(): ModelRuntimeSignature {
        val selection = service<ModelSettings>().getModelSelectionForFeature(FeatureType.AGENT)
        val model = selection.llmModel
        return ModelRuntimeSignature(
            selectionId = selection.selectionId,
            modelId = model.id,
            contextLength = model.contextLength,
            maxOutputTokens = model.maxOutputTokens,
            usesResponsesApi = model.supports(LLMCapability.OpenAIEndpoint.Responses)
        )
    }

    private fun logCheckpointLoadFailure(sessionId: String, agentId: String, ex: Throwable) {
        logger.error(
            "Agent checkpoints: failed to load for sessionId=$sessionId agentId=$agentId error=${ex.message}",
            ex
        )
    }
}
