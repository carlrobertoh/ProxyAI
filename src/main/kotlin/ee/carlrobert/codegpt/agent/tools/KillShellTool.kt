package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class KillShellTool(
    workingDirectory: String,
    hookManager: HookManager,
) : BaseTool<KillShellTool.Args, KillShellTool.Result>(
    workingDirectory = workingDirectory,
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "KillShell",
    description = """
- Kills a running background bash shell by its ID
- Takes a shell_id parameter identifying the shell to kill
- Returns a success or failure status
- Use this tool when you need to terminate a long-running shell
- Shell IDs can be found using the Bash tool with run_in_background=true
""".trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager
) {

    @Serializable
    data class Args(
        @property:LLMDescription(
            "The ID of the background shell to kill"
        )
        @SerialName("bash_id")
        val bashId: String
    )

    @Serializable
    data class Result(
        @SerialName("bash_id")
        val bashId: String,
        val success: Boolean,
        val message: String
    )

    override suspend fun doExecute(args: Args): Result = withContext(Dispatchers.IO) {
        val process = BackgroundProcessManager.getProcess(args.bashId)
            ?: return@withContext Result(
                bashId = args.bashId,
                success = false,
                message = "Shell not found. The shell may have already completed or the ID is invalid."
            )
        if (!process.isAlive) {
            val exitCode = runCatching { process.exitValue() }.getOrNull()
            return@withContext Result(
                bashId = args.bashId,
                success = false,
                message = "Shell has already completed${exitCode?.let { " with exit code $it" } ?: ""}"
            )
        }

        return@withContext try {
            val terminated = BackgroundProcessManager.terminateProcess(args.bashId)
            if (terminated) {
                Result(
                    bashId = args.bashId,
                    success = true,
                    message = "Shell successfully terminated."
                )
            } else {
                Result(
                    bashId = args.bashId,
                    success = false,
                    message = "Failed to terminate shell. The process may have already completed."
                )
            }
        } catch (e: Exception) {
            Result(
                bashId = args.bashId,
                success = false,
                message = "Error terminating shell: ${e.message}"
            )
        }
    }

    override fun createDeniedResult(
        originalArgs: Args,
        deniedReason: String
    ): Result {
        return Result(
            bashId = originalArgs.bashId,
            success = false,
            message = deniedReason
        )
    }

    override fun encodeResultToString(result: Result): String =
        buildString {
            appendLine("Shell ID: ${result.bashId}")

            if (result.success) {
                appendLine("✓ ${result.message}")
            } else {
                appendLine("✗ ${result.message}")
            }
        }.trimEnd().truncateToolResult()
}
