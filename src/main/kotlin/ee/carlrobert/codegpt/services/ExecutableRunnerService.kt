package ee.carlrobert.codegpt.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.Throws

@Service(Service.Level.PROJECT)
class ExecutableRunnerService {

    private val executorService = Executors.newCachedThreadPool()
    private val currentProcess = AtomicReference<Process?>()
    private val isRunning = AtomicBoolean(false)

    interface ProcessOutputHandler {
        fun onStandardOutput(line: String)
        fun onErrorOutput(line: String)
        fun onProcessStarted()
        fun onProcessFinished(exitCode: Int)
        fun onProcessFailed(exception: Exception)
    }

    fun runExecutable(
        executable: String,
        arguments: List<String>,
        workingDirectory: Path? = null,
        outputHandler: ProcessOutputHandler
    ): CompletableFuture<Int> {
        return if (isRunning.compareAndSet(false, true)) {
            CompletableFuture.supplyAsync({
                try {
                    outputHandler.onProcessStarted()
                    executeProcess(executable, arguments, workingDirectory, outputHandler)
                } catch (e: Exception) {
                    LOG.error("Failed to execute process", e)
                    outputHandler.onProcessFailed(e)
                    -1
                } finally {
                    isRunning.set(false)
                    currentProcess.set(null)
                }
            }, executorService)
        } else {
            CompletableFuture.completedFuture(-1)
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun executeProcess(
        executable: String,
        arguments: List<String>,
        workingDirectory: Path?,
        outputHandler: ProcessOutputHandler
    ): Int {
        val command = mutableListOf(executable).apply { addAll(arguments) }

        val processBuilder = ProcessBuilder(command).apply {
            workingDirectory?.let { directory(it.toFile()) }
            redirectErrorStream(false)
        }

        LOG.info("Executing command: ${command.joinToString(" ")}")

        val process = processBuilder.start()
        currentProcess.set(process)

        val streamReadingFuture = ProcessStreamReader.readProcessStreams(process, outputHandler)

        val exitCode = process.waitFor()
        streamReadingFuture.join()

        outputHandler.onProcessFinished(exitCode)

        return exitCode
    }

    fun isRunning(): Boolean = isRunning.get()

    fun stopCurrentProcess() {
        currentProcess.get()?.let { process ->
            if (process.isAlive) {
                process.destroyForcibly()
                isRunning.set(false)
            }
        }
    }

    fun runCommand(
        command: String,
        outputHandler: ProcessOutputHandler
    ): CompletableFuture<Int> {
        return runCommand(command, null, outputHandler)
    }

    fun runCommand(
        command: String,
        workingDirectory: Path?,
        outputHandler: ProcessOutputHandler
    ): CompletableFuture<Int> {
        val parts = command.split("\\s+".toRegex())
        if (parts.isEmpty()) {
            return CompletableFuture.completedFuture(-1)
        }

        val executable = parts[0]
        val arguments = parts.drop(1)

        return runExecutable(executable, arguments, workingDirectory, outputHandler)
    }

    fun dispose() {
        stopCurrentProcess()
        executorService.shutdown()
    }

    companion object {
        private val LOG = Logger.getInstance(ExecutableRunnerService::class.java)

        @JvmStatic
        fun getInstance(project: Project): ExecutableRunnerService = project.service()
    }
}