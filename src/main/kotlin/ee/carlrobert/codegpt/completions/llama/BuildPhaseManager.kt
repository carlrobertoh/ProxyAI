package ee.carlrobert.codegpt.completions.llama

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTPlugin
import ee.carlrobert.codegpt.completions.llama.LlamaConstants.BUILD_CONFIGURATION
import ee.carlrobert.codegpt.completions.llama.LlamaConstants.BUILD_DIRECTORY
import ee.carlrobert.codegpt.completions.llama.LlamaConstants.BUILD_PARALLEL_JOBS
import ee.carlrobert.codegpt.completions.llama.LlamaConstants.PROGRESS_CMAKE_BUILD
import ee.carlrobert.codegpt.completions.llama.LlamaConstants.PROGRESS_CMAKE_SETUP
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList

class BuildPhaseManager(
    private val infoLogger: (String) -> Unit,
    private val errorLogger: (String) -> Unit,
    private val phaseUpdater: (String) -> Unit,
    private val progressStopper: () -> Unit
) {

    companion object {
        private val logger = thisLogger()

        private fun getAbsoluteBuildPath(): String {
            return Paths.get(CodeGPTPlugin.getLlamaSourcePath(), BUILD_DIRECTORY).toAbsolutePath()
                .toString()
        }

        private fun isCMakeCacheConflict(buildPath: String): Boolean {
            val cacheFile = File(buildPath, "CMakeCache.txt")
            if (!cacheFile.exists()) return false

            try {
                val cacheContent = cacheFile.readText()
                val currentSourcePath = CodeGPTPlugin.getLlamaSourcePath()

                val homeDirectoryRegex = """CMAKE_HOME_DIRECTORY:INTERNAL=(.+)""".toRegex()
                val match = homeDirectoryRegex.find(cacheContent)

                return match?.groupValues?.get(1)?.let { cachedPath ->
                    !Paths.get(cachedPath).toAbsolutePath()
                        .equals(Paths.get(currentSourcePath).toAbsolutePath())
                } == true
            } catch (e: Exception) {
                logger.warn("Failed to read CMake cache file", e)
                return false
            }
        }

        private fun cleanupCMakeCache(buildPath: String) {
            try {
                val buildDir = File(buildPath)
                if (buildDir.exists()) {
                    logger.info("Cleaning up CMake cache due to path mismatch: $buildPath")
                    Files.walk(buildDir.toPath())
                        .sorted(Comparator.reverseOrder())
                        .map { it.toFile() }
                        .forEach { it.delete() }
                    buildDir.mkdirs()
                }
            } catch (e: Exception) {
                logger.warn("Failed to cleanup CMake cache", e)
            }
        }
    }

    @Throws(ExecutionException::class)
    fun executeCMakeSetup(
        params: LlamaServerStartupParams,
        indicator: ProgressIndicator,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ): OSProcessHandler {

        indicator.text = CodeGPTBundle.get("llama.build.cmake.setup")
        indicator.fraction = PROGRESS_CMAKE_SETUP

        phaseUpdater(CodeGPTBundle.get("llama.build.cmake.setup"))
        infoLogger("=== " + CodeGPTBundle.get("llama.build.startingBuild") + " ===")
        infoLogger(CodeGPTBundle.get("llama.build.phase.setup"))
        infoLogger(CodeGPTBundle.get("llamaServerAgent.buildingProject.description"))

        val buildPath = getAbsoluteBuildPath()
        if (isCMakeCacheConflict(buildPath)) {
            infoLogger(CodeGPTBundle.get("llama.build.cache.cleanup"))
            cleanupCMakeCache(buildPath)
        }

        val handler = OSProcessHandler(getCMakeSetupCommandLine(params))
        handler.addProcessListener(object : ProcessAdapter() {
            private val errorLines = CopyOnWriteArrayList<String>()

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (ProcessOutputType.isStderr(outputType)) {
                    errorLines.add(event.text)
                    errorLogger(event.text.trim())
                    return
                }
                logger.info(event.text)
                infoLogger(event.text.trim())
            }

            override fun processTerminated(event: ProcessEvent) {
                val exitCode = event.exitCode
                val exitMessage = "CMake setup exited with code $exitCode"
                logger.info(exitMessage)

                if (exitCode != 0) {
                    errorLogger(exitMessage)
                    phaseUpdater(CodeGPTBundle.get("llama.build.phase.setupFailed"))
                    progressStopper()
                    onError(errorLines.joinToString(","))
                } else {
                    infoLogger(exitMessage)
                    onSuccess()
                }
            }
        })

        return handler
    }

    @Throws(ExecutionException::class)
    fun executeCMakeBuild(
        params: LlamaServerStartupParams,
        indicator: ProgressIndicator,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ): OSProcessHandler {

        indicator.text = CodeGPTBundle.get("llama.build.cmake.build")
        indicator.fraction = PROGRESS_CMAKE_BUILD

        phaseUpdater(CodeGPTBundle.get("llama.build.cmake.build"))
        infoLogger(CodeGPTBundle.get("llama.build.phase.build"))

        val handler = OSProcessHandler(getCMakeBuildCommandLine(params))
        handler.addProcessListener(object : ProcessAdapter() {
            private val errorLines = CopyOnWriteArrayList<String>()

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (ProcessOutputType.isStderr(outputType)) {
                    errorLines.add(event.text)
                    errorLogger(event.text.trim())
                    return
                }
                logger.info(event.text)
                infoLogger(event.text.trim())
            }

            override fun processTerminated(event: ProcessEvent) {
                val exitCode = event.exitCode
                val exitMessage = "Server build exited with code $exitCode"
                logger.info(exitMessage)

                if (exitCode != 0) {
                    errorLogger(exitMessage)
                    phaseUpdater(CodeGPTBundle.get("llama.build.phase.buildFailed"))
                    progressStopper()
                    onError(errorLines.joinToString(","))
                } else {
                    infoLogger(exitMessage)
                    onSuccess()
                }
            }
        })

        return handler
    }

    private fun getCMakeSetupCommandLine(params: LlamaServerStartupParams): GeneralCommandLine {
        val absoluteBuildPath = getAbsoluteBuildPath()
        return GeneralCommandLine().apply {
            charset = StandardCharsets.UTF_8
            exePath = "cmake"
            withWorkDirectory(CodeGPTPlugin.getLlamaSourcePath())
            addParameters("-B", absoluteBuildPath)
            withEnvironment(params.additionalEnvironmentVariables())
            isRedirectErrorStream = false
        }
    }

    private fun getCMakeBuildCommandLine(params: LlamaServerStartupParams): GeneralCommandLine {
        val absoluteBuildPath = getAbsoluteBuildPath()
        return GeneralCommandLine().apply {
            charset = StandardCharsets.UTF_8
            exePath = "cmake"
            withWorkDirectory(CodeGPTPlugin.getLlamaSourcePath())
            addParameters(
                "--build",
                absoluteBuildPath,
                "--config",
                BUILD_CONFIGURATION,
                "-j",
                BUILD_PARALLEL_JOBS.toString()
            )
            withEnvironment(params.additionalEnvironmentVariables())
            isRedirectErrorStream = false
        }
    }
}