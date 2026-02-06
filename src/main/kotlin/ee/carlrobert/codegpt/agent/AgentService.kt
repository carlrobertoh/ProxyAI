package ee.carlrobert.codegpt.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.prompt.executor.clients.LLMClientException
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.history.AgentCheckpointHistoryService
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.AgentToolWindowContentManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.McpTagDetails
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path

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
        val modelId: String,
        val selectedServerIds: Set<String>,
        val events: AgentEvents
    )

    private val sessionJobs = ConcurrentHashMap<String, Job>()
    private val pendingMessages = ConcurrentHashMap<String, ArrayDeque<MessageWithContext>>()
    private val sessionAgents = ConcurrentHashMap<String, AIAgent<MessageWithContext, String>>()
    private val sessionRuntimes = ConcurrentHashMap<String, SessionRuntime>()
    internal var runtimeFactory: AgentRuntimeFactory = AgentRuntimeFactory { p, storage, provider, events, sid, pending ->
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
        pendingMessages.getOrPut(sessionId) { ArrayDeque() }.add(message)
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
        updateMcpContext(sessionId, message)

        if (isSessionRunning(sessionId)) {
            addToQueue(message, sessionId)
            return
        }

        val provider = service<ModelSelectionService>().getServiceForFeature(FeatureType.AGENT)
        val contentManager = project.service<AgentToolWindowContentManager>()
        val runtimeAgentId = contentManager.getSession(sessionId)?.runtimeAgentId
        val runtime = ensureSessionRuntime(sessionId, provider, events)

        val agent = runCatching {
            runBlocking {
                runtime.service.createAgent(id = runtimeAgentId)
            }
        }.onFailure { ex ->
            logger.warn("Failed to create managed agent for session=$sessionId", ex)
        }.getOrNull() ?: return

        contentManager.setRuntimeAgentId(sessionId, agent.id)
        sessionAgents[sessionId] = agent
        sessionJobs[sessionId] = CoroutineScope(Dispatchers.IO).launch {
            try {
                agent.run(message)
            } catch (ex: LLMClientException) {
                events.onClientException(provider, ex)
            } catch (_: CancellationException) {
                return@launch
            } catch (ex: Exception) {
                logger.error(ex)
            } finally {
                refreshSessionResumeCheckpoint(sessionId, agent.id)
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
        project.service<AgentMcpContextService>().clear(sessionId)
    }

    fun isSessionRunning(sessionId: String): Boolean {
        return sessionJobs[sessionId]?.isActive == true
    }

    fun getPendingMessages(sessionId: String): List<MessageWithContext> {
        return pendingMessages[sessionId]?.toList() ?: emptyList()
    }

    fun clearPendingMessages(sessionId: String) {
        pendingMessages.remove(sessionId)
    }

    fun getAgentForSession(sessionId: String): AIAgent<MessageWithContext, String>? {
        return sessionAgents[sessionId]
    }

    private fun updateMcpContext(sessionId: String, message: MessageWithContext) {
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
    }

    private suspend fun refreshSessionResumeCheckpoint(sessionId: String, agentId: String) {
        val ref = runCatching {
            historyService.loadLatestResumeCheckpoint(agentId)
                ?.let { CheckpointRef(agentId, it.checkpointId) }
        }.onFailure { ex ->
            logger.warn(
                "Agent checkpoints: failed to refresh session checkpoint session=$sessionId " +
                        "agentId=$agentId error=${ex.message}",
                ex
            )
        }.getOrNull()

        if (ref != null) {
            project.service<AgentToolWindowContentManager>().setResumeCheckpointRef(sessionId, ref)
        }
    }

    private fun ensureSessionRuntime(
        sessionId: String,
        provider: ServiceType,
        events: AgentEvents
    ): SessionRuntime {
        val modelId = service<ModelSelectionService>().getAgentModel().id
        val selectedServerIds = project.service<AgentMcpContextService>()
            .get(sessionId)
            ?.selectedServerIds
            ?: emptySet()
        val existing = sessionRuntimes[sessionId]
        if (existing != null &&
            existing.provider == provider &&
            existing.modelId == modelId &&
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
            modelId = modelId,
            selectedServerIds = selectedServerIds,
            events = events
        )
        sessionRuntimes[sessionId] = created
        return created
    }

    private fun logCheckpointLoadFailure(sessionId: String, agentId: String, ex: Throwable) {
        val sessionInfo = sessionId.let { " session=$it" }
        logger.error(
            "Agent checkpoints: failed to load for$sessionInfo agentId=$agentId error=${ex.message}",
            ex
        )
    }
}
