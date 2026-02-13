package ee.carlrobert.codegpt.agent.rollback

import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Paths
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

@Service(Service.Level.PROJECT)
class RollbackService(private val project: Project) {

    private val activeRuns = ConcurrentHashMap<String, RunTracker>()
    private val activeRunsBySession = ConcurrentHashMap<String, String>()
    private val snapshotsByRunId = ConcurrentHashMap<String, SnapshotState>()
    private val latestSnapshotRunIdBySession = ConcurrentHashMap<String, String>()

    private val MAX_TRACKABLE_BYTES = 10 * 1024 * 1024

    fun trackEdit(
        sessionId: String,
        filePath: String,
        originalContent: String
    ) {
        val runId = activeRunsBySession[sessionId] ?: return
        trackEditForRun(runId, filePath, originalContent)
    }

    fun trackEditForRun(
        runId: String,
        filePath: String,
        originalContent: String
    ) {
        val tracker = activeRuns[runId] ?: return
        val normalizedPath = filePath.replace("\\", "/")
        tracker.recordExplicitEdit(normalizedPath, originalContent)
    }

    fun trackWrite(sessionId: String, filePath: String) {
        val runId = activeRunsBySession[sessionId] ?: return
        trackWriteForRun(runId, filePath)
    }

    fun trackWriteForRun(runId: String, filePath: String) {
        val tracker = activeRuns[runId] ?: return
        val normalizedPath = filePath.replace("\\", "/")
        tracker.recordExplicitWrite(normalizedPath)
    }

    fun startSession(sessionId: String): String {
        val labelText = "ProxyAI: Agent run ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}"
        val label = LocalHistory.getInstance().putSystemLabel(project, labelText)
        val runId = UUID.randomUUID().toString()
        val tracker = RunTracker(runId, sessionId, Instant.now(), labelText, label)
        activeRuns[runId] = tracker
        activeRunsBySession[sessionId] = runId
        latestSnapshotRunIdBySession.remove(sessionId)
        return runId
    }

    fun finishSession(sessionId: String): RollbackSnapshot? {
        val runId = activeRunsBySession.remove(sessionId) ?: return getSnapshot(sessionId)
        return finishRun(runId)
    }

    fun finishRun(runId: String): RollbackSnapshot? {
        val tracker = activeRuns.remove(runId) ?: return getRunSnapshot(runId)
        val snapshot = SnapshotState(
            runId = runId,
            sessionId = tracker.sessionId,
            label = tracker.label,
            labelRef = tracker.labelRef,
            startedAt = tracker.startedAt,
            completedAt = Instant.now(),
            changes = tracker.changes.toMap()
        )
        snapshotsByRunId[runId] = snapshot
        latestSnapshotRunIdBySession[tracker.sessionId] = runId
        return snapshot.toSnapshot()
    }

    fun getSnapshot(sessionId: String): RollbackSnapshot? {
        val runId = latestSnapshotRunIdBySession[sessionId] ?: return null
        return snapshotsByRunId[runId]?.toSnapshot()
    }

    fun getRunSnapshot(runId: String): RollbackSnapshot? =
        snapshotsByRunId[runId]?.toSnapshot()

    fun clearSnapshot(sessionId: String) {
        val runId = latestSnapshotRunIdBySession.remove(sessionId) ?: return
        snapshotsByRunId.remove(runId)
    }

    fun clearRunSnapshot(runId: String) {
        val snapshot = snapshotsByRunId.remove(runId) ?: return
        latestSnapshotRunIdBySession.remove(snapshot.sessionId, runId)
    }

    fun getDiffData(sessionId: String, path: String): RollbackDiffData? {
        val snapshot = snapshotForSession(sessionId) ?: return null
        val change = snapshot.changes[path] ?: return null
        if (change.kind != ChangeKind.DELETED && !isTrackable(path)) return null
        val beforeText = when (change.kind) {
            ChangeKind.ADDED -> ""
            else -> {
                val original = change.originalContent
                if (original != null && original.isNotEmpty()) {
                    decodeContent(original)
                } else {
                    decodeLabelContent(
                        snapshot.labelRef,
                        change.originalPath ?: path,
                        change.originalContent
                    )
                }
            }
        }
        val afterText = when (change.kind) {
            ChangeKind.DELETED -> ""
            else -> readCurrentText(path)
        }
        return RollbackDiffData(
            path = path,
            beforeText = beforeText,
            afterText = if (change.kind == ChangeKind.DELETED) "" else afterText
        )
    }

    fun isRollbackAvailable(sessionId: String): Boolean =
        snapshotForSession(sessionId)?.changes?.isNotEmpty() == true &&
                !activeRunsBySession.containsKey(sessionId)

    fun isDisplayable(path: String): Boolean = isTrackable(path)

    suspend fun rollbackFile(sessionId: String, path: String): RollbackResult =
        withContext(Dispatchers.IO) {
            val snapshot = snapshotForSession(sessionId)
                ?: return@withContext RollbackResult.Failure("No rollback snapshot available")
            val change = snapshot.changes[path]
                ?: return@withContext RollbackResult.Failure("No change tracked for $path")

            val errors = applyChanges(snapshot.labelRef, mapOf(path to change))

            if (errors.isNotEmpty()) {
                RollbackResult.Failure(errors.joinToString("\n"))
            } else {
                val updated = snapshot.changes.toMutableMap()
                updated.remove(path)
                if (updated.isEmpty()) {
                    clearRunSnapshot(snapshot.runId)
                } else {
                    snapshotsByRunId[snapshot.runId] = snapshot.copy(changes = updated.toMap())
                }
                RollbackResult.Success("Rollback completed")
            }
        }

    suspend fun rollbackSession(sessionId: String): RollbackResult = withContext(Dispatchers.IO) {
        val snapshot = snapshotForSession(sessionId)
            ?: return@withContext RollbackResult.Failure("No rollback snapshot available")

        val errors = applyChanges(snapshot.labelRef, snapshot.changes)

        if (errors.isNotEmpty()) {
            RollbackResult.Failure(errors.joinToString("\n"))
        } else {
            clearRunSnapshot(snapshot.runId)
            RollbackResult.Success("Rollback completed")
        }
    }

    suspend fun rollbackRun(runId: String): RollbackResult = withContext(Dispatchers.IO) {
        val snapshot = snapshotsByRunId[runId]
            ?: return@withContext RollbackResult.Failure("No rollback snapshot available")

        val errors = applyChanges(snapshot.labelRef, snapshot.changes)

        if (errors.isNotEmpty()) {
            RollbackResult.Failure(errors.joinToString("\n"))
        } else {
            clearRunSnapshot(runId)
            RollbackResult.Success("Rollback completed")
        }
    }

    private fun isTrackable(path: String): Boolean {
        val file = LocalFileSystem.getInstance().findFileByPath(path)
        if (file != null) {
            if (file.isDirectory || !file.isValid) return false
            if (FileTypeManager.getInstance().isFileIgnored(file)) return false
            if (project.service<ProxyAISettingsService>().isPathIgnored(file.path)) return false
            return file.length <= MAX_TRACKABLE_BYTES
        }

        val fileName = runCatching { Paths.get(path).fileName?.toString() }
            .getOrNull()
            ?: path.substringAfterLast('/')
        if (FileTypeManager.getInstance().isFileIgnored(fileName)) return false
        if (project.service<ProxyAISettingsService>().isPathIgnored(path)) return false

        val ioFile = File(path)
        if (!ioFile.exists()) return false
        return ioFile.length() <= MAX_TRACKABLE_BYTES
    }

    private fun decodeContent(content: ByteArray?): String {
        if (content == null) return ""
        return runCatching { String(content, Charsets.UTF_8) }.getOrDefault("")
    }

    private fun decodeLabelContent(label: Label, path: String, fallback: ByteArray?): String {
        val bytes = resolveLabelContent(label, path, fallback) ?: return ""
        return decodeContent(bytes)
    }

    private fun readCurrentText(path: String): String {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return ""
        val docText = runReadAction {
            FileDocumentManager.getInstance().getDocument(vf)?.text
        }
        if (docText != null) return docText
        return runCatching { VfsUtilCore.loadText(vf) }.getOrDefault("")
    }

    private fun deleteFile(path: String, errors: MutableList<String>) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return
        if (!vf.exists()) return
        runCatching {
            runInEdt(ModalityState.defaultModalityState()) {
                runWriteAction {
                    vf.delete(this)
                }
            }
        }.onFailure {
            errors.add("Failed to delete $path: ${it.message}")
        }
    }

    private fun restoreFile(path: String?, content: ByteArray?, errors: MutableList<String>) {
        if (path.isNullOrBlank()) return
        if (content == null) {
            errors.add("Missing original content for $path")
            return
        }

        val ioFile = File(path)
        val parent = ioFile.parentFile
        if (parent != null && !parent.exists()) {
            runCatching { parent.mkdirs() }.onFailure {
                errors.add("Failed to create parent directories for $path: ${it.message}")
                return
            }
        }

        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            ?: runCatching {
                val parentPath = parent?.path ?: run {
                    errors.add("Missing parent directory for $path")
                    return@runCatching null
                }
                val parentVf = LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(parentPath) ?: return@runCatching null
                parentVf.createChildData(this, ioFile.name)
            }.getOrNull()

        if (file == null) {
            errors.add("Failed to recreate file $path")
            return
        }

        runCatching {
            runInEdt(ModalityState.defaultModalityState()) {
                runWriteAction {
                    file.setBinaryContent(content)
                }
            }
        }.onFailure {
            errors.add("Failed to restore $path: ${it.message}")
        }
    }

    private fun applyChangeWithLabel(
        label: Label,
        path: String,
        change: TrackedChange,
        errors: MutableList<String>
    ) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
        when (change.kind) {
            ChangeKind.ADDED -> {
                if (file != null) {
                    val reverted = runCatching { label.revert(project, file) }.isSuccess
                    if (!reverted) deleteFile(path, errors)
                } else {
                    deleteFile(path, errors)
                }
            }

            ChangeKind.MODIFIED -> {
                if (file != null) {
                    val reverted = runCatching {
                        label.revert(project, file)
                    }.isSuccess
                    if (!reverted) {
                        val before = resolveLabelContent(label, path, change.originalContent)
                        restoreFile(path, before, errors)
                    }
                } else {
                    val before = resolveLabelContent(label, path, change.originalContent)
                    restoreFile(path, before, errors)
                }
            }

            ChangeKind.DELETED -> {
                val before = resolveLabelContent(label, path, change.originalContent)
                restoreFile(path, before, errors)
            }

            ChangeKind.MOVED -> {
                deleteFile(path, errors)
                val originalPath = change.originalPath
                if (originalPath != null) {
                    val before = resolveLabelContent(label, originalPath, change.originalContent)
                    restoreFile(originalPath, before, errors)
                }
            }
        }
    }

    private fun resolveLabelContent(
        label: Label,
        path: String,
        fallback: ByteArray?
    ): ByteArray? {
        val labelContent = runCatching { label.getByteContent(path) }
            .getOrNull()?.bytes
        return if (labelContent == null || labelContent.isEmpty()) {
            fallback
        } else {
            labelContent
        }
    }

    private fun applyChanges(
        label: Label,
        changes: Map<String, TrackedChange>
    ): List<String> {
        val errors = mutableListOf<String>()
        runInEdt(ModalityState.defaultModalityState()) {
            runWriteAction {
                changes.forEach { (path, change) ->
                    applyChangeWithLabel(label, path, change, errors)
                }
            }
        }
        return errors
    }

    private fun snapshotForSession(sessionId: String): SnapshotState? {
        val runId = latestSnapshotRunIdBySession[sessionId] ?: return null
        return snapshotsByRunId[runId]
    }

    companion object {
        fun getInstance(project: Project): RollbackService {
            return project.getService(RollbackService::class.java)
        }
    }

    private class RunTracker(
        val runId: String,
        val sessionId: String,
        val startedAt: Instant,
        val label: String,
        val labelRef: Label,
        val changes: MutableMap<String, TrackedChange> = ConcurrentHashMap()
    ) {
        fun recordExplicitEdit(filePath: String, originalContent: String) {
            val existing = changes[filePath]
            if (existing?.kind == ChangeKind.ADDED) return
            if (existing?.kind == ChangeKind.MOVED) return
            if (existing?.kind == ChangeKind.MODIFIED) return
            changes[filePath] = TrackedChange(
                kind = ChangeKind.MODIFIED,
                originalPath = null,
                originalContent = originalContent.toByteArray(Charsets.UTF_8)
            )
        }

        fun recordExplicitWrite(filePath: String) {
            val existing = changes[filePath]
            val file = File(filePath)

            if (existing?.kind == ChangeKind.DELETED) {
                changes[filePath] = existing.copy(kind = ChangeKind.MODIFIED)
                return
            }

            if (existing == null) {
                val originalContent = if (file.exists()) {
                    runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                } else null
                changes[filePath] = TrackedChange(
                    kind = if (file.exists()) ChangeKind.MODIFIED else ChangeKind.ADDED,
                    originalPath = null,
                    originalContent = originalContent?.toByteArray(Charsets.UTF_8)
                )
            }
        }
    }

    private data class TrackedChange(
        val kind: ChangeKind,
        val originalPath: String?,
        val originalContent: ByteArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TrackedChange

            if (kind != other.kind) return false
            if (originalPath != other.originalPath) return false
            if (!originalContent.contentEquals(other.originalContent)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = kind.hashCode()
            result = 31 * result + (originalPath?.hashCode() ?: 0)
            result = 31 * result + (originalContent?.contentHashCode() ?: 0)
            return result
        }
    }

    private data class SnapshotState(
        val runId: String,
        val sessionId: String,
        val label: String,
        val labelRef: Label,
        val startedAt: Instant,
        val completedAt: Instant,
        val changes: Map<String, TrackedChange>
    ) {
        fun toSnapshot(): RollbackSnapshot? {
            if (changes.isEmpty()) return null
            return RollbackSnapshot(
                runId = runId,
                sessionId = sessionId,
                label = label,
                startedAt = startedAt,
                completedAt = completedAt,
                changes = changes.map { (path, change) ->
                    FileChange(
                        path = path,
                        kind = change.kind,
                        originalPath = change.originalPath
                    )
                }.sortedBy { it.path }
            )
        }
    }
}

data class RollbackSnapshot(
    val runId: String,
    val sessionId: String,
    val label: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val changes: List<FileChange>
)

data class FileChange(
    val path: String,
    val kind: ChangeKind,
    val originalPath: String?
)

data class RollbackDiffData(
    val path: String,
    val beforeText: String,
    val afterText: String
)

enum class ChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
    MOVED
}

sealed class RollbackResult {
    data class Success(val message: String) : RollbackResult()
    data class Failure(val message: String) : RollbackResult()
}
