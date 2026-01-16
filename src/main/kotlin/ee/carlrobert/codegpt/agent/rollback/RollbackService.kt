package ee.carlrobert.codegpt.agent.rollback

import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
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
 */
@Service(Service.Level.PROJECT)
class RollbackService(private val project: Project) {

    private val activeRuns = ConcurrentHashMap<String, RunTracker>()
    private val snapshots = ConcurrentHashMap<String, SnapshotState>()
    private val settingsService = project.getService(ProxyAISettingsService::class.java)

    @Volatile
    private var isApplyingRollback = false

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun before(events: List<VFileEvent>) {
                if (isApplyingRollback || activeRuns.isEmpty()) return
                events.forEach { event ->
                    if (!isInProject(event)) return@forEach
                    when (event) {
                        is VFileContentChangeEvent -> recordModified(event.file)
                        is VFileDeleteEvent -> recordDeleted(event.file)
                        is VFileMoveEvent -> {
                            val oldPath = "${event.oldParent.path}/${event.file.name}"
                            val newPath = "${event.newParent.path}/${event.file.name}"
                            recordMoved(event.file, oldPath, newPath)
                        }

                        is VFilePropertyChangeEvent -> {
                            if (event.propertyName == VirtualFile.PROP_NAME) {
                                val oldName = event.oldValue as? String ?: return@forEach
                                val newName = event.newValue as? String ?: return@forEach
                                val parentPath = event.file.parent?.path ?: return@forEach
                                recordMoved(
                                    event.file,
                                    "$parentPath/$oldName",
                                    "$parentPath/$newName"
                                )
                            }
                        }
                    }
                }
            }

            override fun after(events: List<VFileEvent>) {
                if (isApplyingRollback || activeRuns.isEmpty()) return
                events.forEach { event ->
                    if (!isInProject(event)) return@forEach
                    if (event is VFileCreateEvent) {
                        recordCreated(event.path, event.isDirectory)
                    }
                }
            }
        })
    }

    private fun isInProject(event: VFileEvent): Boolean {
        val basePath = project.basePath ?: return false
        val normalizedBase = FileUtil.toSystemIndependentName(basePath)
        
        val filePath = when (event) {
            is VFileContentChangeEvent, is VFileDeleteEvent -> event.file?.path
            is VFileMoveEvent -> event.file.path
            is VFilePropertyChangeEvent -> event.file.path
            is VFileCreateEvent -> event.path
            else -> return false
        } ?: return false

        val normalizedPath = FileUtil.toSystemIndependentName(filePath)
        return FileUtil.isAncestor(normalizedBase, normalizedPath, false) || normalizedPath == normalizedBase
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
        if (!isTrackable(path)) return null
        val snapshot = snapshots[sessionId] ?: return null
        val change = snapshot.changes[path] ?: return null
        val beforeText = when (change.kind) {
            ChangeKind.ADDED -> ""
            else -> decodeLabelContent(
                snapshot.labelRef,
                change.originalPath ?: path,
                change.originalContent
            )
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

    private fun recordModified(file: VirtualFile) {
        if (!isTrackable(file)) return
        activeRuns.values.forEach { it.recordModified(file) }
    }

    private fun recordDeleted(file: VirtualFile) {
        if (!isTrackable(file)) return
        activeRuns.values.forEach { it.recordDeleted(file) }
    }

    private fun recordCreated(path: String, isDirectory: Boolean) {
        if (isDirectory || !isTrackable(path)) return
        activeRuns.values.forEach { it.recordCreated(path) }
    }

    private fun recordMoved(file: VirtualFile, oldPath: String, newPath: String) {
        if (!isTrackable(file)) return
        val oldNormalized = oldPath.replace("\\", "/")
        val newNormalized = newPath.replace("\\", "/")
        if (!isTrackable(oldNormalized) && !isTrackable(newNormalized)) return
        activeRuns.values.forEach { it.recordMoved(file, oldNormalized, newNormalized) }
    }

    private fun isTrackable(file: VirtualFile): Boolean {
        if (file.isDirectory || !file.isValid) return false
        if (FileTypeManager.getInstance().isFileIgnored(file)) return false
        if (ChangeListManager.getInstance(project).isIgnoredFile(file)) return false
        if (settingsService.isPathIgnored(file.path)) return false
        return !file.path.contains(".proxyai/checkpoints/")
    }

    private fun isTrackable(path: String): Boolean {
        val file = LocalFileSystem.getInstance().findFileByPath(path)
        if (file != null) return isTrackable(file)

        val basePath = project.basePath ?: return false
        val normalized = FileUtil.toSystemIndependentName(path)
        val normalizedBase = FileUtil.toSystemIndependentName(basePath)
        if (!FileUtil.isAncestor(
                normalizedBase,
                normalized,
                false
            ) && normalized != normalizedBase
        ) {
            return false
        }
        val fileName = runCatching { Paths.get(normalized).fileName?.toString() }
            .getOrNull()
            ?: normalized.substringAfterLast('/')
        if (FileTypeManager.getInstance().isFileIgnored(fileName)) {
            return false
        }
        if (settingsService.isPathIgnored(normalized)) return false
        return !normalized.contains(".proxyai/checkpoints/")
    }

    private fun readContentSafe(file: VirtualFile): ByteArray? =
        runCatching { file.contentsToByteArray() }.getOrNull()

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
                val parentVf = VfsUtil.createDirectories(parentPath)
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
        return runCatching { label.getByteContent(path) }
            .getOrNull()?.bytes ?: fallback
    }

    companion object {
        fun getInstance(project: Project): RollbackService {
            return project.getService(RollbackService::class.java)
        }
    }

    private inner class RunTracker(
        val sessionId: String,
        val startedAt: Instant,
        val label: String,
        val labelRef: Label,
        val changes: MutableMap<String, TrackedChange> = ConcurrentHashMap()
    ) {
        fun recordModified(file: VirtualFile) {
            val path = file.path
            val existing = changes[path]
            if (existing?.kind == ChangeKind.ADDED) return
            if (existing?.kind == ChangeKind.MOVED) return
            if (existing?.kind == ChangeKind.MODIFIED) return
            changes[path] = TrackedChange(
                kind = ChangeKind.MODIFIED,
                originalPath = null,
                originalContent = readContentSafe(file)
            )
        }

        fun recordDeleted(file: VirtualFile) {
            val path = file.path
            val existing = changes[path]
            if (existing?.kind == ChangeKind.ADDED) {
                changes.remove(path)
                return
            }
            if (existing?.kind == ChangeKind.DELETED) return

            val original = existing?.originalContent ?: readContentSafe(file)
            changes[path] = TrackedChange(
                kind = ChangeKind.DELETED,
                originalPath = null,
                originalContent = original
            )
        }

        fun recordCreated(path: String) {
            val existing = changes[path]
            if (existing?.kind == ChangeKind.DELETED) {
                changes[path] = existing.copy(kind = ChangeKind.MODIFIED)
                return
            }
            if (existing == null) {
                changes[path] = TrackedChange(
                    kind = ChangeKind.ADDED,
                    originalPath = null,
                    originalContent = null
                )
            }
        }

        fun recordMoved(file: VirtualFile, oldPath: String, newPath: String) {
            if (oldPath == newPath) return
            val existing = changes.remove(oldPath)

            if (existing?.kind == ChangeKind.ADDED) {
                changes[newPath] = existing.copy(kind = ChangeKind.ADDED)
                return
            }

            val content = existing?.originalContent ?: readContentSafe(file)
            changes[newPath] = TrackedChange(
                kind = ChangeKind.MOVED,
                originalPath = oldPath,
                originalContent = content
            )
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
