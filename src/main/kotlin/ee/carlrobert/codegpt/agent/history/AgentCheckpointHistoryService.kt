package ee.carlrobert.codegpt.agent.history

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.isTombstone
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.Path
import ai.koog.prompt.message.Message as PromptMessage

@Service(Service.Level.PROJECT)
class AgentCheckpointHistoryService(project: Project) {

    companion object {
        private val logger = thisLogger()
    }

    private val root = Path(project.basePath ?: "", ".proxyai")
    private val checkpointsRoot = root.resolve("checkpoints")
    private val storage = JVMFilePersistenceStorageProvider(root)

    @Volatile
    private var cachedSummaries: List<AgentHistoryThreadSummary>? = null

    suspend fun listThreads(limit: Int? = null): List<AgentHistoryThreadSummary> =
        withContext(Dispatchers.IO) {
            val summaries = loadThreadSummaries()

            return@withContext if (limit == null) summaries else summaries.take(limit)
        }

    suspend fun listThreadsPage(
        query: String,
        offset: Int,
        limit: Int,
        refresh: Boolean = false
    ): AgentHistoryPage = withContext(Dispatchers.IO) {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1)
        val summaries = getCachedSummaries(refresh)
        val filtered = filterSummaries(summaries, query)
        if (safeOffset >= filtered.size) {
            return@withContext AgentHistoryPage(emptyList(), false, filtered.size)
        }
        val endIndex = (safeOffset + safeLimit).coerceAtMost(filtered.size)
        AgentHistoryPage(
            items = filtered.subList(safeOffset, endIndex),
            hasMore = endIndex < filtered.size,
            total = filtered.size
        )
    }

    suspend fun loadCheckpoint(ref: CheckpointRef): AgentCheckpointData? =
        withContext(Dispatchers.IO) {
            val checkpoints = storage.getCheckpoints(ref.agentId)
                .filterNot { it.isTombstone() }
            checkpoints.firstOrNull { it.checkpointId == ref.checkpointId }
        }

    suspend fun loadResumeCheckpoint(ref: CheckpointRef): AgentCheckpointData? =
        withContext(Dispatchers.IO) {
            val checkpoints = storage.getCheckpoints(ref.agentId)
                .filterNot { it.isTombstone() }
                .sortedByDescending { it.createdAt }
            if (checkpoints.isEmpty()) return@withContext null

            val preferred = checkpoints.firstOrNull { it.checkpointId == ref.checkpointId }
            if (preferred == null) {
                return@withContext checkpoints.firstOrNull { it.isResumableNode() }
            }
            if (preferred.isResumableNode()) {
                return@withContext preferred
            }

            checkpoints
                .asSequence()
                .filter { it.createdAt <= preferred.createdAt }
                .firstOrNull { it.isResumableNode() }
                ?: checkpoints.firstOrNull { it.isResumableNode() }
        }

    suspend fun loadLatestResumeCheckpoint(agentId: String): AgentCheckpointData? =
        withContext(Dispatchers.IO) {
            val checkpoints = storage.getCheckpoints(agentId)
                .filterNot { it.isTombstone() }
            checkpoints
                .filter { it.isResumableNode() }
                .maxByOrNull { it.createdAt }
                ?: checkpoints.maxByOrNull { it.createdAt }
        }

    private suspend fun buildSummary(agentId: String): AgentHistoryThreadSummary? {
        val checkpoints = storage.getCheckpoints(agentId)
            .filterNot { it.isTombstone() }
        if (checkpoints.isEmpty()) {
            return null
        }

        val latest = checkpoints.maxBy { it.createdAt }
        val status = when (latest.nodePath.substringAfterLast('/')) {
            "__finish__" -> ThreadStatus.COMPLETED
            "__start__" -> ThreadStatus.UNKNOWN
            else -> ThreadStatus.PARTIAL
        }

        val title = extractThreadTitle(latest.messageHistory)

        val lastAssistant = latest.messageHistory
            .asReversed()
            .firstNotNullOfOrNull { msg ->
                when (msg) {
                    is PromptMessage.Assistant -> toSingleLine(msg.content).takeIf { it.isNotBlank() }
                    is PromptMessage.Reasoning -> toSingleLine(msg.content).takeIf { it.isNotBlank() }
                    else -> null
                }
            } ?: ""

        return AgentHistoryThreadSummary(
            agentId = agentId,
            latest = CheckpointRef(agentId, latest.checkpointId),
            latestCreatedAt = latest.createdAt,
            status = status,
            runCount = checkpoints.size,
            title = title.take(80),
            preview = lastAssistant.take(160)
        )
    }

    private fun extractThreadTitle(history: List<PromptMessage>): String {
        val todoWriteTitle = history
            .asReversed()
            .firstNotNullOfOrNull { message ->
                if (message !is PromptMessage.Tool.Call || !isTodoWriteTool(message.tool)) {
                    return@firstNotNullOfOrNull null
                }
                extractTodoWriteTitle(message.content)
            }

        if (!todoWriteTitle.isNullOrBlank()) {
            return toSingleLine(todoWriteTitle)
        }

        return history
            .asReversed()
            .firstNotNullOfOrNull { message ->
                val user = message as? PromptMessage.User ?: return@firstNotNullOfOrNull null
                if (shouldHideInAgentToolWindow(user)) {
                    return@firstNotNullOfOrNull null
                }
                user.content
                    .let(::toSingleLine)
                    .takeIf { it.isNotBlank() }
            } ?: "Recovered conversation"
    }

    private fun isTodoWriteTool(toolName: String): Boolean {
        return toolName.equals("TodoWrite", ignoreCase = true) ||
                toolName.equals("TodoWriteTool", ignoreCase = true)
    }

    private fun extractTodoWriteTitle(rawArgs: String): String? {
        val args = runCatching {
            Json.parseToJsonElement(rawArgs).jsonObject
        }.getOrNull() ?: return null

        return args["title"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun toSingleLine(value: String): String {
        return value.replace("\\s+".toRegex(), " ").trim()
    }

    private fun AgentCheckpointData.isResumableNode(): Boolean {
        return nodePath.substringAfterLast('/') != "__finish__"
    }

    private suspend fun loadThreadSummaries(): List<AgentHistoryThreadSummary> {
        if (!Files.exists(checkpointsRoot)) {
            return emptyList()
        }

        val agentIds = withContext(Dispatchers.IO) {
            Files.list(checkpointsRoot)
        }.use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .toList()
        }

        return agentIds.mapNotNull { agentId ->
            runCatching { buildSummary(agentId) }
                .onFailure { logger.warn("Failed to load checkpoints for agentId=$agentId", it) }
                .getOrNull()
        }.sortedByDescending { it.latestCreatedAt }
    }

    private suspend fun getCachedSummaries(refresh: Boolean): List<AgentHistoryThreadSummary> {
        if (!refresh) {
            synchronized(this) {
                cachedSummaries?.let { return it }
            }
        }

        val loaded = loadThreadSummaries()
        synchronized(this) {
            if (refresh || cachedSummaries == null) {
                cachedSummaries = loaded
            }
            return cachedSummaries ?: loaded
        }
    }

    private fun filterSummaries(
        summaries: List<AgentHistoryThreadSummary>,
        query: String
    ): List<AgentHistoryThreadSummary> {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return summaries
        }
        return summaries.filter { item ->
            item.title.contains(normalized, ignoreCase = true) ||
                    item.preview.contains(normalized, ignoreCase = true) ||
                    item.agentId.contains(normalized, ignoreCase = true)
        }
    }
}
