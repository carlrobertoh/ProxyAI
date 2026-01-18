package ee.carlrobert.codegpt.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.prompt.executor.clients.LLMClientException
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.conversations.message.TokenUsageTracker
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path

@Service(Service.Level.PROJECT)
class AgentService(private val project: Project) {

    companion object {
        private val logger = thisLogger()
    }

    private val sessionJobs = ConcurrentHashMap<String, Job>()
    private val pendingMessages = ConcurrentHashMap<String, ArrayDeque<MessageWithContext>>()
    private val sessionAgents = ConcurrentHashMap<String, AIAgent<MessageWithContext, String>>()
    private val sessionTokenTrackers = ConcurrentHashMap<String, TokenUsageTracker>()
    private val checkpointStorage =
        JVMFilePersistenceStorageProvider(Path(project.basePath ?: "", ".proxyai"))

    private val _queuedMessageProcessed = MutableSharedFlow<String>()
    val queuedMessageProcessed = _queuedMessageProcessed.asSharedFlow()

    fun addToQueue(message: MessageWithContext, sessionId: String) {
        pendingMessages.getOrPut(sessionId) { ArrayDeque() }.add(message)
    }

    suspend fun getCheckpoint(sessionId: String): AgentCheckpointData? {
        val prevAgentId = sessionAgents[sessionId]?.id ?: return null
        return runCatching {
            checkpointStorage.getCheckpoints(prevAgentId)
                .filter { it.nodePath != "tombstone" }
                .maxByOrNull { it.createdAt }
        }.onFailure { ex ->
            val sessionInfo = sessionId.let { " session=$it" }
            logger.error(
                "Agent checkpoints: failed to load for$sessionInfo agentId=$prevAgentId " +
                        "error=${ex.message}",
                ex
            )
        }.getOrNull()
    }

    fun submitMessage(message: MessageWithContext, events: AgentEvents, sessionId: String) {
        if (isSessionRunning(sessionId)) {
            addToQueue(message, sessionId)
            return
        }

        val provider = service<ModelSelectionService>().getServiceForFeature(FeatureType.AGENT)
        val previousCheckpoint = runBlocking { getCheckpoint(sessionId) }

        val agent = ProxyAIAgent.create(
            project,
            checkpointStorage,
            previousCheckpoint,
            provider,
            events,
            sessionId,
            pendingMessages
        )
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
        sessionTokenTrackers.remove(sessionId)
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

    fun getTokenTrackerForSession(sessionId: String): TokenUsageTracker {
        return sessionTokenTrackers.getOrPut(sessionId) { TokenUsageTracker() }
    }
}
