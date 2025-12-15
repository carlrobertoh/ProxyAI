package ee.carlrobert.codegpt.agent.tools

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

object BackgroundProcessManager {

    private val logger = thisLogger()
    private val processes = ConcurrentHashMap<String, Process>()
    private val outputBuffers = ConcurrentHashMap<String, ProcessOutput>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class ProcessOutput(
        val stdout: StringBuilder = StringBuilder(),
        val stderr: StringBuilder = StringBuilder(),
        var exitCode: Int? = null,
        var isComplete: Boolean = false
    )

    fun registerProcess(id: String, process: Process) {
        processes[id] = process
        outputBuffers[id] = ProcessOutput()

        scope.launch {
            try {
                collectProcessOutput(process, id)
            } catch (e: Exception) {
                logger.error("Could not register process with id: $id", e)
                cleanup(id)
            }
        }
    }

    fun getProcess(id: String): Process? = processes[id]

    fun getOutput(id: String): ProcessOutput? = outputBuffers[id]

    fun terminateProcess(id: String): Boolean = try {
        processes[id]?.let { process ->
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
        cleanup(id)
        true
    } catch (e: Exception) {
        logger.error("Could not terminate process with id: $id", e)
        false
    }

    private fun cleanup(id: String) {
        processes.remove(id)
        scope.launch {
            delay(30000)
            outputBuffers.remove(id)
        }
    }

    private suspend fun collectProcessOutput(process: Process, bashId: String) {
        val output = outputBuffers[bashId] ?: return

        withContext(Dispatchers.IO) {
            val stdoutJob = launch {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            output.stdout.appendLine(line)
                        }
                    }
                } catch (_: Exception) {
                    // Stream closed, ignore
                }
            }

            val stderrJob = launch {
                try {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            output.stderr.appendLine(line)
                        }
                    }
                } catch (_: Exception) {
                    // Stream closed, ignore
                }
            }

            try {
                process.waitFor()
                output.exitCode = process.exitValue()
                output.isComplete = true
            } catch (_: Exception) {
                output.isComplete = true
            }

            stdoutJob.join()
            stderrJob.join()
        }
    }
}