package ee.carlrobert.codegpt.agent.external.host

import com.agentclientprotocol.model.ReadTextFileRequest
import com.agentclientprotocol.model.SessionId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AcpFileHostTest {

    @Test
    fun `readTextFile prefers editor content over disk content`() {
        val cwd = Files.createTempDirectory("acp-host-file")
        val file = cwd.resolve("Example.txt")
        Files.writeString(file, "disk content")
        val host = AcpFileHost(
            openDocumentReader = AcpOpenDocumentReader { path ->
                if (path == file) "editor content" else null
            },
            writer = AcpTextFileWriter { _, _ -> error("unexpected write") }
        )

        val response = host.readTextFile(
            AcpHostSessionContext(sessionId = "session-1", cwd = cwd),
            ReadTextFileRequest(
                sessionId = SessionId("session-1"),
                path = file.toString()
            )
        )

        assertEquals("editor content", response.content)
    }

    @Test
    fun `readTextFile applies line window after resolving editor content`() {
        val cwd = Files.createTempDirectory("acp-host-file")
        val file = cwd.resolve("Example.txt")
        val host = AcpFileHost(
            openDocumentReader = AcpOpenDocumentReader { path ->
                if (path == file) "line1\nline2\nline3\nline4" else null
            },
            writer = AcpTextFileWriter { _, _ -> error("unexpected write") }
        )

        val response = host.readTextFile(
            AcpHostSessionContext(sessionId = "session-1", cwd = cwd),
            ReadTextFileRequest(
                sessionId = SessionId("session-1"),
                path = file.toString(),
                line = 2u,
                limit = 2u
            )
        )

        assertEquals("line2\nline3", response.content)
    }

}
