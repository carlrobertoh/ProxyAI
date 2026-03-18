package ee.carlrobert.codegpt.mcp

import ee.carlrobert.codegpt.util.CommandRuntimeHelper

object McpPathHelper {

    fun createEnvironment(
        serverEnvironmentVariables: Map<String, String>,
        resolvedCommand: String? = null
    ): MutableMap<String, String> {
        return CommandRuntimeHelper.createEnvironment(
            extraEnvironment = serverEnvironmentVariables,
            resolvedCommand = resolvedCommand,
            includeResolvedCommandParent = false
        )
    }
}
