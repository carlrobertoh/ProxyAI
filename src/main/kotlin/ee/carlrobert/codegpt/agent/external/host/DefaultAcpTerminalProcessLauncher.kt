package ee.carlrobert.codegpt.agent.external.host

import com.intellij.execution.configurations.GeneralCommandLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class DefaultAcpTerminalProcessLauncher(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : AcpTerminalProcessLauncher {
    override fun launch(
        command: String,
        args: List<String>,
        cwd: Path,
        env: Map<String, String>,
        outputByteLimit: ULong?
    ): AcpTerminalProcess {
        val process = GeneralCommandLine().apply {
            withExePath(command)
            withParameters(args)
            withWorkDirectory(cwd.toFile())
            withEnvironment(env)
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            withRedirectErrorStream(true)
        }.createProcess()

        return DefaultAcpTerminalProcess(
            terminalId = UUID.randomUUID().toString(),
            process = process,
            scope = scope,
            outputByteLimit = outputByteLimit
        )
    }
}

private class DefaultAcpTerminalProcess(
    override val terminalId: String,
    private val process: Process,
    scope: CoroutineScope,
    outputByteLimit: ULong?
) : AcpTerminalProcess {

    private val outputBuffer = BoundedOutputBuffer(outputByteLimit)
    private val exitStatus = AtomicReference<AcpTerminalExitStatus?>(null)
    private val stdoutJob = scope.launch {
        collect(process.inputStream)
    }
    private val stderrJob = scope.launch {
        collect(process.errorStream)
    }

    override fun output(): AcpTerminalOutputSnapshot {
        if (exitStatus.get() == null && !process.isAlive) {
            exitStatus.compareAndSet(
                null,
                AcpTerminalExitStatus(exitCode = process.exitValue().toUInt())
            )
        }
        return outputBuffer.snapshot(exitStatus.get())
    }

    override suspend fun waitForExit(): AcpTerminalExitStatus {
        val exitCode = withContext(Dispatchers.IO) {
            process.waitFor()
        }
        joinAll(stdoutJob, stderrJob)
        val status = AcpTerminalExitStatus(exitCode = exitCode.toUInt())
        exitStatus.set(status)
        return status
    }

    override fun release() {
    }

    override fun kill() {
        process.destroyForcibly()
    }

    private suspend fun collect(stream: InputStream) {
        withContext(Dispatchers.IO) {
            stream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    outputBuffer.appendLine(line)
                }
            }
        }
    }
}

private class BoundedOutputBuffer(
    private val byteLimit: ULong?
) {
    private val text = StringBuilder()
    private var bytesUsed: ULong = 0u
    private var truncated = false

    @Synchronized
    fun appendLine(line: String) {
        if (truncated) return

        val encoded = (line + '\n').toByteArray(StandardCharsets.UTF_8)
        val limit = byteLimit
        if (limit == null) {
            text.appendLine(line)
            return
        }

        val remaining = limit - bytesUsed
        if (remaining == 0uL) {
            truncated = true
            return
        }

        if (encoded.size.toUInt().toULong() <= remaining) {
            text.appendLine(line)
            bytesUsed += encoded.size.toUInt().toULong()
            return
        }

        val allowed = remaining.toInt().coerceAtMost(encoded.size)
        text.append(String(encoded, 0, allowed, StandardCharsets.UTF_8))
        bytesUsed = limit
        truncated = true
    }

    @Synchronized
    fun snapshot(exitStatus: AcpTerminalExitStatus? = null): AcpTerminalOutputSnapshot {
        return AcpTerminalOutputSnapshot(
            output = text.toString(),
            truncated = truncated,
            exitStatus = exitStatus
        )
    }
}
