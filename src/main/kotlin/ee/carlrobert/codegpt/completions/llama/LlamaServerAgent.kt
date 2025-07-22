package ee.carlrobert.codegpt.completions.llama

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.util.application
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.completions.llama.LlamaConstants.PROGRESS_SERVER_START
import ee.carlrobert.codegpt.completions.llama.logging.NoOpLoggingStrategy
import ee.carlrobert.codegpt.completions.llama.logging.ServerLoggingStrategy
import ee.carlrobert.codegpt.services.llama.ServerLogsManager
import ee.carlrobert.codegpt.settings.service.llama.form.LlamaSettingsForm

@Service
class LlamaServerAgent : Disposable {

    companion object {
        private val logger = thisLogger()
    }

    private val buildPhaseManager: BuildPhaseManager = BuildPhaseManager(
        infoLogger = { message -> logToConsole(message, false, true) },
        errorLogger = { message -> logToConsole(message, true, true) },
        phaseUpdater = { phase -> updatePhase(phase) },
        progressStopper = { stopProgress() }
    )

    private val processManager: LlamaProcessManager = LlamaProcessManager(
        infoLogger = { message -> logToConsole(message, false, false) },
        errorLogger = { message -> logToConsole(message, true, false) }
    )

    @Volatile
    private var stoppedByUser: Boolean = false

    @Volatile
    private var buildInProgress: Boolean = false

    @Volatile
    private var currentProgressIndicator: ProgressIndicator? = null

    @Volatile
    private var setupProcessHandler: OSProcessHandler? = null

    @Volatile
    private var buildProcessHandler: OSProcessHandler? = null

    private var loggingStrategy: ServerLoggingStrategy = NoOpLoggingStrategy
    private var settingsForm: LlamaSettingsForm? = null

    fun startAgent(
        params: LlamaServerStartupParams,
        loggingStrategy: ServerLoggingStrategy = NoOpLoggingStrategy,
        onSuccess: Runnable,
        onServerStopped: Runnable
    ) {
        this.loggingStrategy = loggingStrategy

        application.service<ServerLogsManager>().startNewSession()

        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(null, CodeGPTBundle.get("llama.build.startingBuild"), true) {
                override fun run(indicator: ProgressIndicator) {
                    currentProgressIndicator = indicator
                    indicator.isIndeterminate = false
                    buildServer(params, indicator, onSuccess, onServerStopped)
                }
            })
    }

    private fun buildServer(
        params: LlamaServerStartupParams,
        indicator: ProgressIndicator,
        onSuccess: Runnable,
        onServerStopped: Runnable
    ) {
        try {
            stoppedByUser = false
            buildInProgress = true

            loggingStrategy.startProgress()

            if (indicator.isCanceled) {
                stoppedByUser = true
                return
            }

            setupProcessHandler = buildPhaseManager.executeCMakeSetup(params, indicator, {
                if (stoppedByUser) {
                    buildInProgress = false
                    clearProcessHandlers()
                    logToConsole(CodeGPTBundle.get("llama.server.buildStopped"), false, true)
                    onServerStopped.run()
                    return@executeCMakeSetup
                }

                if (indicator.isCanceled) {
                    stoppedByUser = true
                    return@executeCMakeSetup
                }

                try {
                    buildProcessHandler = buildPhaseManager.executeCMakeBuild(params, indicator, {
                        if (stoppedByUser) {
                            buildInProgress = false
                            logToConsole(CodeGPTBundle.get("llama.server.buildStopped"), false, true)
                            onServerStopped.run()
                            return@executeCMakeBuild
                        }

                        indicator.text = CodeGPTBundle.get("llama.server.starting")
                        indicator.fraction = PROGRESS_SERVER_START

                        loggingStrategy.setPhase(CodeGPTBundle.get("llama.server.starting"))

                        if (indicator.isCanceled) {
                            stoppedByUser = true
                            return@executeCMakeBuild
                        }

                        try {
                            processManager.startServer(params, {
                                loggingStrategy.apply {
                                    setPhase(CodeGPTBundle.get("llama.server.running"))
                                    indicator.text = CodeGPTBundle.get("llama.server.running")
                                    indicator.fraction = 1.0
                                    stopProgress()
                                }

                                settingsForm?.refreshServerStatus()
                                buildInProgress = false
                                clearProcessHandlers()
                                onSuccess.run()
                            }) { errorText ->
                                showServerError(errorText, onServerStopped)
                            }
                        } catch (e: ExecutionException) {
                            showServerError(e.message ?: "Unknown error", onServerStopped)
                        }
                    }) { errorText ->
                        showServerError(errorText, onServerStopped)
                    }

                    buildProcessHandler?.startNotify()
                } catch (e: ExecutionException) {
                    showServerError(e.message ?: "Unknown error", onServerStopped)
                }
            }) { errorText ->
                showServerError(errorText, onServerStopped)
            }

            setupProcessHandler?.startNotify()
        } catch (e: ExecutionException) {
            showServerError(e.message ?: "Unknown error", onServerStopped)
        }
    }

    fun stopAgent() {
        stoppedByUser = true
        buildInProgress = false

        currentProgressIndicator?.cancel()

        setupProcessHandler?.let { handler ->
            if (!handler.isProcessTerminated) {
                handler.destroyProcess()
                logToConsole(CodeGPTBundle.get("llama.server.stopping.cmake"), false, true)
            }
        }

        buildProcessHandler?.let { handler ->
            if (!handler.isProcessTerminated) {
                handler.destroyProcess()
                logToConsole(CodeGPTBundle.get("llama.server.stopping.build"), false, true)
            }
        }

        processManager.stopServer()

        currentProgressIndicator = null
        setupProcessHandler = null
        buildProcessHandler = null
    }

    val isServerRunning: Boolean
        get() = processManager.isServerRunning()

    val isBuildInProgress: Boolean
        get() = buildInProgress

    private fun showServerError(errorText: String, onServerStopped: Runnable) {
        buildInProgress = false
        clearProcessHandlers()

        loggingStrategy.apply {
            setPhase(CodeGPTBundle.get("llama.server.startupFailed"))
            stopProgress()
        }

        logToConsole(CodeGPTBundle.get("llama.error.server.startupWithDetails", errorText), true, false)
        onServerStopped.run()

        val enhancedErrorMessage = CodeGPTBundle.get("llama.error.server.startup", errorText)

        logger.info(enhancedErrorMessage)
        logToConsole(enhancedErrorMessage, true, false)
    }

    private fun clearProcessHandlers() {
        currentProgressIndicator = null
        setupProcessHandler = null
        buildProcessHandler = null
    }

    fun setSettingsForm(settingsForm: LlamaSettingsForm?) {
        this.settingsForm = settingsForm
    }

    @Synchronized
    private fun logToConsole(message: String, isError: Boolean, isBuildLog: Boolean = false) {
        application.service<ServerLogsManager>().log(message, isError)
        loggingStrategy.logMessage(message, isError, isBuildLog)
    }

    override fun dispose() {
        try {
            stopAgent()
        } catch (e: Exception) {
            logger.error("Error during disposal", e)
        }
    }

    private fun updatePhase(phase: String) {
        loggingStrategy.setPhase(phase)
    }

    private fun stopProgress() {
        loggingStrategy.stopProgress()
    }
}