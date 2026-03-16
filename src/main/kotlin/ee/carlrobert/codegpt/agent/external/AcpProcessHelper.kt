package ee.carlrobert.codegpt.agent.external

import ee.carlrobert.codegpt.util.CommandRuntimeHelper

object AcpProcessHelper {

    fun resolveCommand(
        command: String,
        extraEnvironment: Map<String, String> = emptyMap()
    ): String? {
        return CommandRuntimeHelper.resolveCommand(command, extraEnvironment)
    }

    fun createEnvironment(
        extraEnvironment: Map<String, String>,
        resolvedCommand: String
    ): MutableMap<String, String> {
        return CommandRuntimeHelper.createEnvironment(
            extraEnvironment = extraEnvironment,
            resolvedCommand = resolvedCommand
        )
    }

    fun getCommandNotFoundMessage(command: String): String {
        return buildString {
            append("Command '$command' not found. ")
            when (command) {
                "npx", "node" -> {
                    append("Node.js/npm is required for this ACP runtime. ")
                    append("Ensure it is installed and available to the IDE process. ")
                    append("You can also point the runtime to an absolute executable path.")
                }

                else -> {
                    append("Ensure it is installed and available to the IDE process. ")
                    append("You can also point the runtime to an absolute executable path.")
                }
            }
        }
    }
}
