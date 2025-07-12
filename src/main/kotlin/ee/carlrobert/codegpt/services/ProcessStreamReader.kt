package ee.carlrobert.codegpt.services

import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.services.ExecutableRunnerService.ProcessOutputHandler
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture

object ProcessStreamReader {

    private val logger = thisLogger()

    @JvmStatic
    fun readStreamAsync(
        inputStream: InputStream,
        isError: Boolean,
        outputHandler: ProcessOutputHandler
    ): CompletableFuture<Void?> {
        return CompletableFuture.runAsync {
            try {
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        if (isError) {
                            outputHandler.onErrorOutput(line)
                        } else {
                            outputHandler.onStandardOutput(line)
                        }
                    }
                }
            } catch (e: IOException) {
                val streamType = if (isError) "stderr" else "stdout"
                logger.warn("Error reading $streamType", e)
            }
        }
    }

    @JvmStatic
    fun readProcessStreams(
        process: Process,
        outputHandler: ProcessOutputHandler
    ): CompletableFuture<Void?> {
        val stdoutFuture = readStreamAsync(process.inputStream, false, outputHandler)
        val stderrFuture = readStreamAsync(process.errorStream, true, outputHandler)

        return CompletableFuture.allOf(stdoutFuture, stderrFuture)
    }
}