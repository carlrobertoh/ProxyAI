package ee.carlrobert.codegpt.agent.rollback

import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.agent.tools.WriteTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Paths
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks file changes during agent runs and provides rollback to a checkpoint.
 *
 * Uses IntelliJ's LocalHistory labels for persistence and a lightweight in-memory
 * snapshot of modified files for fast restore.
 * 
 * Now uses direct tracking via trackEdit() and trackWrite() methods instead of
 * VFS event monitoring to avoid capturing build artifacts and temporary files.
 */
@Service(Service.Level.PROJECT)
class RollbackService(private val project: Project) {

    private val activeRuns = ConcurrentHashMap<String, RunTracker>()
    private val snapshots = ConcurrentHashMap<String, SnapshotState>()
    private val settingsService = project.getService(ProxyAISettingsService::class.java)

    @Volatile
    private var isApplyingRollback = false

    private val MAX_TRACKABLE_BYTES = 10 * 1024 * 1024 // 10MB

    init {
        // No VFS listener - using direct tracking via trackEdit() and trackWrite()
    }

    /**
     * Track an EditTool operation directly from the agent.
     * This captures the original file content before the edit is applied.
     */
    fun trackEdit(
        sessionId: String,
        filePath: String,
        originalContent: String
    ) {
        val tracker = activeRuns[sessionId] ?: return
        val normalizedPath = filePath.replace("\\", "/")
        tracker.recordExplicitEdit(normalizedPath,  originalContent)
    }

    /**
     * Track a WriteTool operation directly from the agent.
     * This determines if the file was new or existing before the write.
     */
    fun trackWrite(sessionId: String, filePath: String, args: WriteTool.Args) {
        val tracker = activeRuns[sessionId] ?: return
        val normalizedPath = filePath.replace("\\", "/")
        tracker.recordExplicitWrite(normalizedPath, args)
    }

    fun startSession(sessionId: String) {
        val labelText = "ProxyAI: Agent run ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}"
        val label = LocalHistory.getInstance().putSystemLabel(project, labelText)
        activeRuns[sessionId] = RunTracker(sessionId, Instant.now(), labelText, label)
        snapshots.remove(sessionId)
    }

    fun finishSession(sessionId: String): RollbackSnapshot? {
        val tracker = activeRuns.remove(sessionId) ?: return getSnapshot(sessionId)
        val snapshot = SnapshotState(
            sessionId = sessionId,
            label = tracker.label,
            labelRef = tracker.labelRef,
            startedAt = tracker.startedAt,
            completedAt = Instant.now(),
            changes = tracker.changes.toMap()
        )
        snapshots[sessionId] = snapshot
        return snapshot.toSnapshot()
    }

    fun getSnapshot(sessionId: String): RollbackSnapshot? =
        snapshots[sessionId]?.toSnapshot()

    fun clearSnapshot(sessionId: String) {
        snapshots.remove(sessionId)
    }

    fun getDiffData(sessionId: String, path: String): RollbackDiffData? {
        val snapshot = snapshots[sessionId] ?: return null
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
        snapshots[sessionId]?.changes?.isNotEmpty() == true && !activeRuns.containsKey(sessionId)

    fun isDisplayable(path: String): Boolean = isTrackable(path)

    suspend fun rollbackFile(sessionId: String, path: String): RollbackResult =
        withContext(Dispatchers.IO) {
            val snapshot = snapshots[sessionId]
                ?: return@withContext RollbackResult.Failure("No rollback snapshot available")
            val change = snapshot.changes[path]
                ?: return@withContext RollbackResult.Failure("No change tracked for $path")

            val errors = mutableListOf<String>()
            runInEdt {
                runWriteAction {
                    try {
                        isApplyingRollback = true
                        applyChangeWithLabel(snapshot.labelRef, path, change, errors)
                    } finally {
                        isApplyingRollback = false
                    }
                }
            }

            if (errors.isNotEmpty()) {
                RollbackResult.Failure(errors.joinToString("\n"))
            } else {
                val updated = snapshot.changes.toMutableMap()
                updated.remove(path)
                if (updated.isEmpty()) {
                    snapshots.remove(sessionId)
                } else {
                    snapshots[sessionId] = snapshot.copy(changes = updated.toMap())
                }
                RollbackResult.Success("Rollback completed")
            }
        }

    suspend fun rollbackSession(sessionId: String): RollbackResult = withContext(Dispatchers.Main) {
        val snapshot = snapshots[sessionId]
            ?: return@withContext RollbackResult.Failure("No rollback snapshot available")

        val errors = mutableListOf<String>()
        runInEdt {
            runWriteAction {
                try {
                    isApplyingRollback = true
                    snapshot.changes.forEach { (path, change) ->
                        applyChangeWithLabel(snapshot.labelRef, path, change, errors)
                    }
                } finally {
                    isApplyingRollback = false
                }
            }
        }

        if (errors.isNotEmpty()) {
            RollbackResult.Failure(errors.joinToString("\n"))
        } else {
            snapshots.remove(sessionId)
            RollbackResult.Success("Rollback completed")
        }
    }

    private fun isTrackable(path: String): Boolean {
        val file = LocalFileSystem.getInstance().findFileByPath(path)
        if (file != null) {
            if (file.isDirectory || !file.isValid) return false
            if (FileTypeManager.getInstance().isFileIgnored(file)) return false
            if (settingsService.isPathIgnored(file.path)) return false
            return file.length <= MAX_TRACKABLE_BYTES
        }

        val fileName = runCatching { Paths.get(path).fileName?.toString() }
            .getOrNull()
            ?: path.substringAfterLast('/')
        if (FileTypeManager.getInstance().isFileIgnored(fileName)) return false
        if (settingsService.isPathIgnored(path)) return false

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
                val parentVf = VirtualFileManager.getInstance()
                    .findFileByUrl("file://$parentPath") ?: return@runCatching null
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
        // If label content is null or empty, use fallback (captured at track time)
        return if (labelContent == null || labelContent.isEmpty()) {
            fallback
        } else {
            labelContent
        }
    }

    companion object {
        fun getInstance(project: Project): RollbackService {
            return project.getService(RollbackService::class.java)
        }
    }

    private class RunTracker(
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

        fun recordExplicitWrite(filePath: String, args: WriteTool.Args) {
            val path = filePath.replace("\\", "/")
            val existing = changes[path]
            val file = File(path)
            
            if (existing?.kind == ChangeKind.DELETED) {
                changes[path] = existing.copy(kind = ChangeKind.MODIFIED)
                return
            }

            if (existing == null) {
                val originalContent = if (file.exists()) {
                    runCatching { file.readText() }.getOrNull()
                } else null
                changes[path] = TrackedChange(
                    kind = if (file.exists()) ChangeKind.MODIFIED else ChangeKind.ADDED,
                    originalPath = null,
                    originalContent = originalContent?.toByteArray()
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
