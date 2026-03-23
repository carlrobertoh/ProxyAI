package ee.carlrobert.codegpt.agent.external.host

import com.agentclientprotocol.model.CreateTerminalRequest
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.KillTerminalCommandRequest
import com.agentclientprotocol.model.KillTerminalCommandResponse
import com.agentclientprotocol.model.ReadTextFileRequest
import com.agentclientprotocol.model.ReleaseTerminalRequest
import com.agentclientprotocol.model.ReleaseTerminalResponse
import com.agentclientprotocol.model.TerminalExitStatus
import com.agentclientprotocol.model.TerminalOutputRequest
import com.agentclientprotocol.model.TerminalOutputResponse
import com.agentclientprotocol.model.WaitForTerminalExitRequest
import com.agentclientprotocol.model.WaitForTerminalExitResponse
import com.agentclientprotocol.model.WriteTextFileRequest
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AcpHostTerminalNotFoundException(message: String) : IllegalArgumentException(message)

class AcpTerminalHost(
    private val launcher: AcpTerminalProcessLauncher,
    private val pathPolicy: AcpPathPolicy = AcpPathPolicy()
) {
    private val sessions = ConcurrentHashMap<String, AcpTerminalProcess>()

    fun createTerminal(
        session: AcpHostSessionContext,
        request: CreateTerminalRequest
    ): CreateTerminalResponse {
        require(request.sessionId.value == session.sessionId) {
            "Terminal request session does not match host session"
        }

        val terminalId = "terminal-${UUID.randomUUID()}"
        val process = launcher.launch(
            command = request.command,
            args = request.args,
            cwd = resolveWorkingDirectory(session.cwd, request.cwd),
            env = request.env.associate { it.name to it.value },
            outputByteLimit = request.outputByteLimit
        )
        sessions[terminalId] = process
        return CreateTerminalResponse(terminalId = terminalId)
    }

    fun output(
        session: AcpHostSessionContext,
        request: TerminalOutputRequest
    ): TerminalOutputResponse {
        require(request.sessionId.value == session.sessionId) {
            "Terminal request session does not match host session"
        }
        val process = processFor(session, request.terminalId)
        val snapshot = process.output()
        return TerminalOutputResponse(
            output = snapshot.output,
            truncated = snapshot.truncated,
            exitStatus = snapshot.exitStatus?.let {
                TerminalExitStatus(
                    exitCode = it.exitCode,
                    signal = it.signal
                )
            }
        )
    }

    fun release(
        session: AcpHostSessionContext,
        request: ReleaseTerminalRequest
    ): ReleaseTerminalResponse {
        require(request.sessionId.value == session.sessionId) {
            "Terminal request session does not match host session"
        }
        val process = processFor(session, request.terminalId)
        process.release()
        sessions.remove(request.terminalId)
        return ReleaseTerminalResponse()
    }

    suspend fun waitForExit(
        session: AcpHostSessionContext,
        request: WaitForTerminalExitRequest
    ): WaitForTerminalExitResponse {
        require(request.sessionId.value == session.sessionId) {
            "Terminal request session does not match host session"
        }
        val process = processFor(session, request.terminalId)
        val exit = process.waitForExit()
        return WaitForTerminalExitResponse(
            exitCode = exit.exitCode,
            signal = exit.signal
        )
    }

    fun kill(
        session: AcpHostSessionContext,
        request: KillTerminalCommandRequest
    ): KillTerminalCommandResponse {
        require(request.sessionId.value == session.sessionId) {
            "Terminal request session does not match host session"
        }
        val process = processFor(session, request.terminalId)
        process.kill()
        return KillTerminalCommandResponse()
    }

    private fun processFor(session: AcpHostSessionContext, terminalId: String): AcpTerminalProcess {
        return sessions[terminalId]
            ?: throw AcpHostTerminalNotFoundException(
                "Unknown terminal '$terminalId' for session ${session.sessionId}"
            )
    }

    private fun resolveWorkingDirectory(cwd: Path, overrideCwd: String?): Path {
        return overrideCwd?.takeIf { it.isNotBlank() }
            ?.let { pathPolicy.resolveWithinCwd(it, cwd) }
            ?: cwd.toAbsolutePath().normalize()
    }
}

class AcpHostCapabilities(
    private val fileHost: AcpFileHost,
    private val terminalHost: AcpTerminalHost
) {
    fun clientCapabilities(includeTerminal: Boolean = true) = fileHost.clientCapabilities(includeTerminal)

    fun readTextFile(session: AcpHostSessionContext, request: ReadTextFileRequest) =
        fileHost.readTextFile(session, request)

    fun writeTextFile(session: AcpHostSessionContext, request: WriteTextFileRequest) =
        fileHost.writeTextFile(session, request)

    fun createTerminal(session: AcpHostSessionContext, request: CreateTerminalRequest) =
        terminalHost.createTerminal(session, request)

    fun output(session: AcpHostSessionContext, request: TerminalOutputRequest) =
        terminalHost.output(session, request)

    fun release(session: AcpHostSessionContext, request: ReleaseTerminalRequest) =
        terminalHost.release(session, request)

    suspend fun waitForExit(session: AcpHostSessionContext, request: WaitForTerminalExitRequest) =
        terminalHost.waitForExit(session, request)

    fun kill(session: AcpHostSessionContext, request: KillTerminalCommandRequest) =
        terminalHost.kill(session, request)
}
