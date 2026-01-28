package ee.carlrobert.codegpt.agent.rollback

import com.intellij.openapi.vfs.LocalFileSystem
import ee.carlrobert.codegpt.agent.tools.EditTool
import ee.carlrobert.codegpt.agent.tools.WriteTool
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
            args = EditTool.Args(filePath, "before", "after", "test", false),
            originalContent = "before"
        )
        val snapshot = rollbackService.finishSession(sessionId)

        assertThat(snapshot!!.changes).hasSize(1)
        assertThat(snapshot.changes.single())
            .extracting("path", "kind")
            .containsExactly(filePath, ChangeKind.MODIFIED)
    }

    fun testTrackWriteMarksAddedForNewFile() {
        val filePath = getTestFilePath("tracking_test_new.txt")
        File(filePath).delete()
        val rollbackService = RollbackService(project)
        val sessionId = "s2"
        rollbackService.startSession(sessionId)

        rollbackService.trackWrite(sessionId, filePath, WriteTool.Args(filePath, "hello"))
        val snapshot = rollbackService.finishSession(sessionId)

        assertThat(snapshot).isNotNull
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

        rollbackService.trackWrite(sessionId, filePath, WriteTool.Args(filePath, "after"))
        file.writeText("after")
        LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
        val snapshot = rollbackService.finishSession(sessionId)

        assertThat(snapshot!!.changes.single().kind).isEqualTo(ChangeKind.MODIFIED)
        assertThat(rollbackService.getDiffData(sessionId, filePath))
            .extracting("beforeText", "afterText")
            .containsExactly("before", "after")
    }
}
