package ee.carlrobert.codegpt.completions.llama

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTPlugin
import ee.carlrobert.codegpt.completions.llama.LlamaConstants.SERVER_EXECUTABLE_PATH
import ee.carlrobert.codegpt.completions.llama.LlamaConstants.SERVER_LISTENING_MESSAGE
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import com.intellij.openapi.application.runInEdt

class LlamaProcessManager(
    private val infoLogger: (String) -> Unit,
    private val errorLogger: (String) -> Unit
) {

    companion object {
        private val LOG = Logger.getInstance(LlamaProcessManager::class.java)
    }

    private var serverProcessHandler: OSProcessHandler? = null

    @Throws(ExecutionException::class)
    fun startServer(
        params: LlamaServerStartupParams,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        LOG.info("Booting up llama server")
        infoLogger("Phase 3: Starting Server")
        infoLogger("=== Starting Llama Server ===")
        infoLogger(CodeGPTBundle.get("llamaServerAgent.serverBootup.description"))

        serverProcessHandler = OSProcessHandler.Silent(getServerCommandLine(params)).apply {
            addProcessListener(createServerProcessListener(
                params.port(),
                onSuccess,
                onError
            ))
            startNotify()
        }
    }

    fun stopServer() {
        serverProcessHandler?.let {
            if (!it.isProcessTerminated) {
                it.destroyProcess()
            }
        }
    }

    fun isServerRunning(): Boolean {
        return serverProcessHandler?.let {
            it.isStartNotified && !it.isProcessTerminated
        } == true
    }

    private fun createServerProcessListener(
        port: Int,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ): ProcessListener {
        return object : ProcessAdapter() {
            private val errorLines = CopyOnWriteArrayList<String>()

            override fun processTerminated(event: ProcessEvent) {
                val message = "Server stopped with code ${event.exitCode}"
                LOG.info(message)

                if (event.exitCode != 0) {
                    errorLogger(message)
                    onError(errorLines.joinToString(","))
                } else {
                    infoLogger(message)
                }
            }

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                LOG.debug(event.text)
                infoLogger(event.text.trim())

                if (event.text.contains(SERVER_LISTENING_MESSAGE)) {
                    val successMessage = "Server up and running!"
                    LOG.info(successMessage)
                    infoLogger(successMessage)

                    LlamaSettings.getCurrentState().serverPort = port

                    runInEdt { onSuccess() }
                }
            }
        }
    }

    private fun getServerCommandLine(params: LlamaServerStartupParams): GeneralCommandLine {
        return GeneralCommandLine().apply {
            charset = StandardCharsets.UTF_8
            exePath = SERVER_EXECUTABLE_PATH
            withWorkDirectory(CodeGPTPlugin.getLlamaSourcePath())
            addParameters(
                "-m", params.modelPath(),
                "-c", params.contextLength().toString(),
                "--port", params.port().toString(),
                "-t", params.threads().toString()
            )
            addParameters(params.additionalRunParameters())
            withEnvironment(params.additionalEnvironmentVariables())
            isRedirectErrorStream = false
        }
    }
}