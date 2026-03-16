package ee.carlrobert.codegpt.util

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.util.EnvironmentUtil
import java.io.File

object CommandRuntimeHelper {

    fun resolveCommand(
        command: String,
        extraEnvironment: Map<String, String> = emptyMap()
    ): String? {
        val commandFile = File(command)
        if (commandFile.isAbsolute && commandFile.exists() && commandFile.canExecute()) {
            return commandFile.absolutePath
        }

        val environment = createEnvironment(
            extraEnvironment = extraEnvironment,
            resolvedCommand = null,
            includeResolvedCommandParent = false
        )
        val pathValue = environment["PATH"]

        return if (pathValue.isNullOrBlank()) {
            PathEnvironmentVariableUtil.findInPath(command)?.absolutePath
        } else {
            PathEnvironmentVariableUtil.findInPath(command, pathValue, null)?.absolutePath
                ?: PathEnvironmentVariableUtil.findInPath(command)?.absolutePath
        }
    }

    fun createEnvironment(
        extraEnvironment: Map<String, String>,
        resolvedCommand: String? = null,
        includeResolvedCommandParent: Boolean = true
    ): MutableMap<String, String> {
        val parentEnvironment = EnvironmentUtil.getEnvironmentMap().ifEmpty { System.getenv() }
        val environment = parentEnvironment.toMutableMap()
        environment.putAll(extraEnvironment)
        EnvironmentUtil.inlineParentOccurrences(environment, parentEnvironment)

        if (includeResolvedCommandParent) {
            resolvedCommand
                ?.let(::File)
                ?.parentFile
                ?.takeIf { it.isDirectory }
                ?.absolutePath
                ?.let { appendPathEntry(environment, it) }
        }

        return environment
    }

    private fun appendPathEntry(
        environment: MutableMap<String, String>,
        pathEntry: String
    ) {
        val pathEntries = linkedSetOf<String>()
        val currentPath = environment["PATH"]
        if (!currentPath.isNullOrBlank()) {
            PathEnvironmentVariableUtil.getPathDirs(currentPath).forEach(pathEntries::add)
        }
        pathEntries.add(pathEntry)
        environment["PATH"] = pathEntries.joinToString(File.pathSeparator)
    }
}
