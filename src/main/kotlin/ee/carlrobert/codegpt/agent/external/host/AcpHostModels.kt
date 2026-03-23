package ee.carlrobert.codegpt.agent.external.host

import java.nio.file.Path

data class AcpHostSessionContext(
    val sessionId: String,
    val cwd: Path
)

data class AcpTextFileReadResult(
    val content: String,
    val fromEditor: Boolean
)

data class AcpTerminalExitStatus(
    val exitCode: UInt? = null,
    val signal: String? = null
)

data class AcpTerminalOutputSnapshot(
    val output: String,
    val truncated: Boolean,
    val exitStatus: AcpTerminalExitStatus? = null
)

fun interface AcpOpenDocumentReader {
    fun read(path: Path): String?
}

fun interface AcpTextFileWriter {
    fun write(path: Path, content: String)
}

interface AcpTerminalProcess {
    val terminalId: String

    fun output(): AcpTerminalOutputSnapshot
    suspend fun waitForExit(): AcpTerminalExitStatus
    fun release()
    fun kill()
}

fun interface AcpTerminalProcessLauncher {
    fun launch(
        command: String,
        args: List<String>,
        cwd: Path,
        env: Map<String, String>,
        outputByteLimit: ULong?
    ): AcpTerminalProcess
}

