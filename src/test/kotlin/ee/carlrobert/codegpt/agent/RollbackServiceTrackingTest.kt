package ee.carlrobert.codegpt.agent

import com.intellij.openapi.vfs.LocalFileSystem
import ee.carlrobert.codegpt.agent.rollback.ChangeKind
import ee.carlrobert.codegpt.agent.rollback.RollbackResult
import ee.carlrobert.codegpt.agent.rollback.RollbackService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.io.File

class RollbackServiceTrackingTest : IntegrationTest() {

    private fun getTestFilePath(name: String): String {
        val base = project.basePath ?: throw AssertionError("Project basePath is null")
        val baseDir = File(base)
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return "$base/$name"
    }

    fun testTrackEditRecordsModifiedWithOriginalContent() {
        val filePath = getTestFilePath("tracking_test_a.txt")
        File(filePath).apply { parentFile?.mkdirs(); writeText("before") }
        val rollbackService = RollbackService(project)
        val sessionId = "s1"
        rollbackService.startSession(sessionId)

        rollbackService.trackEdit(
            sessionId = sessionId,
            filePath = filePath,
            originalContent = "before"
        )
        val actualSnapshot = rollbackService.finishSession(sessionId)

        assertThat(actualSnapshot!!.changes).hasSize(1)
        assertThat(actualSnapshot.changes.single())
            .extracting("path", "kind")
            .containsExactly(filePath, ChangeKind.MODIFIED)
    }

    fun testTrackWriteMarksAddedForNewFile() {
        val filePath = getTestFilePath("tracking_test_new.txt")
        File(filePath).delete()
        val rollbackService = RollbackService(project)
        val sessionId = "s2"
        rollbackService.startSession(sessionId)

        rollbackService.trackWrite(sessionId, filePath)
        val actualSnapshot = rollbackService.finishSession(sessionId)

        assertThat(actualSnapshot).isNotNull
            .extracting { it!!.changes.single().kind }
            .isEqualTo(ChangeKind.ADDED)
    }

    fun testTrackWriteMarksModifiedForExistingFileAndCapturesOriginal() {
        val filePath = getTestFilePath("tracking_test_existing.txt")
        val file = File(filePath).apply { parentFile?.mkdirs(); writeText("before") }
        LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
        val rollbackService = RollbackService(project)
        val sessionId = "s3"
        rollbackService.startSession(sessionId)

        rollbackService.trackWrite(sessionId, filePath)
        file.writeText("after")
        LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
        val actualSnapshot = rollbackService.finishSession(sessionId)
        val actualDiff = rollbackService.getDiffData(sessionId, filePath)

        assertThat(actualSnapshot!!.changes.single().kind).isEqualTo(ChangeKind.MODIFIED)
        assertThat(actualDiff)
            .extracting("beforeText", "afterText")
            .containsExactly("before", "after")
    }

    fun testSnapshotsRemainAvailablePerRunWithinSameSession() {
        val rollbackService = RollbackService(project)
        val sessionId = "run-scoped-session"

        val firstPath = getTestFilePath("run_scoped_a.txt")
        File(firstPath).delete()
        val firstRunId = rollbackService.startSession(sessionId)
        rollbackService.trackWrite(sessionId, firstPath)
        File(firstPath).writeText("run-a")
        rollbackService.finishSession(sessionId)

        val secondPath = getTestFilePath("run_scoped_b.txt")
        File(secondPath).delete()
        val secondRunId = rollbackService.startSession(sessionId)
        rollbackService.trackWrite(sessionId, secondPath)
        File(secondPath).writeText("run-b")
        rollbackService.finishSession(sessionId)

        val firstRunSnapshot = rollbackService.getRunSnapshot(firstRunId)
        val secondRunSnapshot = rollbackService.getRunSnapshot(secondRunId)
        val latestSessionSnapshot = rollbackService.getSnapshot(sessionId)

        assertThat(firstRunSnapshot)
            .extracting("runId")
            .isEqualTo(firstRunId)
        assertThat(secondRunSnapshot)
            .extracting("runId")
            .isEqualTo(secondRunId)
        assertThat(latestSessionSnapshot)
            .extracting("runId")
            .isEqualTo(secondRunId)
    }

    fun testRollbackRunDoesNotClearOtherRunSnapshots() {
        val rollbackService = RollbackService(project)
        val sessionId = "run-rollback-session"

        val firstPath = getTestFilePath("run_rollback_a.txt")
        File(firstPath).delete()
        val firstRunId = rollbackService.startSession(sessionId)
        rollbackService.trackWrite(sessionId, firstPath)
        File(firstPath).writeText("run-a")
        rollbackService.finishSession(sessionId)

        val secondPath = getTestFilePath("run_rollback_b.txt")
        File(secondPath).delete()
        val secondRunId = rollbackService.startSession(sessionId)
        rollbackService.trackWrite(sessionId, secondPath)
        File(secondPath).writeText("run-b")
        rollbackService.finishSession(sessionId)

        val actualRollbackResult = runBlocking {
            rollbackService.rollbackRun(firstRunId)
        }
        val firstRunSnapshotAfterRollback = rollbackService.getRunSnapshot(firstRunId)
        val secondRunSnapshotAfterRollback = rollbackService.getRunSnapshot(secondRunId)
        val latestSessionSnapshot = rollbackService.getSnapshot(sessionId)

        assertThat(actualRollbackResult).isInstanceOf(RollbackResult.Success::class.java)
        assertThat(firstRunSnapshotAfterRollback).isNull()
        assertThat(secondRunSnapshotAfterRollback).isNotNull()
        assertThat(latestSessionSnapshot)
            .extracting("runId")
            .isEqualTo(secondRunId)
    }
}
