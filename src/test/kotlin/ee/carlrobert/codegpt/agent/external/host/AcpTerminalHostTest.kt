package ee.carlrobert.codegpt.agent.external.host

import com.agentclientprotocol.model.CreateTerminalRequest
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.SessionId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcpTerminalHostTest {

    @Test
    fun `createTerminal resolves cwd and stores terminal process`() {
        val cwd = Files.createTempDirectory("acp-host-terminal")
        val launcher = FakeTerminalProcessLauncher()
        val host = AcpTerminalHost(launcher)
        val session = AcpHostSessionContext(sessionId = "session-1", cwd = cwd)

        val response = host.createTerminal(
            session,
            CreateTerminalRequest(
                sessionId = SessionId("session-1"),
                command = "git",
                args = listOf("status"),
                cwd = "worktree",
                env = listOf(EnvVariable(name = "FOO", value = "bar")),
                outputByteLimit = 128uL
            )
        )

        assertTrue(response.terminalId.isNotBlank())
        assertEquals(cwd.resolve("worktree").toAbsolutePath().normalize(), launcher.lastCwd)
        assertEquals(mapOf("FOO" to "bar"), launcher.nextEnv)
        assertEquals(128uL, launcher.process.outputByteLimit)
    }

    @Test
    fun `createTerminal rejects session mismatch`() {
        val cwd = Files.createTempDirectory("acp-host-terminal")
        val host = AcpTerminalHost(FakeTerminalProcessLauncher())

        assertFailsWith<IllegalArgumentException> {
            host.createTerminal(
                AcpHostSessionContext(sessionId = "session-1", cwd = cwd),
                CreateTerminalRequest(
                    sessionId = SessionId("different-session"),
                    command = "echo"
                )
            )
        }
    }
}

private class FakeTerminalProcessLauncher : AcpTerminalProcessLauncher {
    val process = FakeTerminalProcess()
    var lastCwd: Path? = null
    var nextEnv: Map<String, String> = emptyMap()

    override fun launch(
        command: String,
        args: List<String>,
        cwd: Path,
        env: Map<String, String>,
        outputByteLimit: ULong?
    ): AcpTerminalProcess {
        lastCwd = cwd
        nextEnv = env
        process.command = command
        process.args = args
        process.cwd = cwd
        process.outputByteLimit = outputByteLimit
        return process
    }
}

private class FakeTerminalProcess : AcpTerminalProcess {
    override val terminalId: String = "fake-terminal"
    var command: String = ""
    var args: List<String> = emptyList()
    var cwd: Path? = null
    var outputByteLimit: ULong? = null
    var snapshot: AcpTerminalOutputSnapshot = AcpTerminalOutputSnapshot("", truncated = false)
    var waitResult: AcpTerminalExitStatus = AcpTerminalExitStatus(exitCode = 0u)
    var killed: Boolean = false
    var released: Boolean = false

    override fun output(): AcpTerminalOutputSnapshot = snapshot

    override suspend fun waitForExit(): AcpTerminalExitStatus = waitResult

    override fun release() {
        released = true
    }

    override fun kill() {
        killed = true
    }
}
