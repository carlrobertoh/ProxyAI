package ee.carlrobert.codegpt.mcp

import com.intellij.openapi.util.SystemInfo
import ee.carlrobert.codegpt.util.CommandRuntimeHelper

object McpCommandValidator {

    fun resolveCommand(
        command: String,
        extraEnvironment: Map<String, String> = emptyMap()
    ): String? {
        return CommandRuntimeHelper.resolveCommand(command, extraEnvironment)
    }

    fun getCommandNotFoundMessage(command: String): String {
        return buildString {
            append("Command '$command' not found. ")

            when (command) {
                "npx", "node" -> {
                    append(
                        if (command == "npx") "Node.js/npm is required for MCP servers. "
                        else "Node.js is required. "
                    )

                    append("Please ensure Node.js is installed and available in PATH. ")
                    append("Visit https://nodejs.org/ for installation instructions. ")

                    if (SystemInfo.isMac) {
                        append("Common installation methods: Homebrew (brew install node), nvm, or volta.")
                    } else if (SystemInfo.isWindows) {
                        append("Common installation methods: Official installer, winget, or volta.")
                    } else {
                        append("Common installation methods: Package manager, nvm, or volta.")
                    }
                }

                else -> {
                    append("Make sure it's installed and available in PATH. ")
                    append("You can also specify the full path to the executable in the MCP settings.")
                }
            }
        }
    }
}
