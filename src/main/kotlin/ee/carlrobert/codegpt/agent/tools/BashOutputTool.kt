package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.application.ApplicationManager
import ee.carlrobert.codegpt.agent.AgentToolOutputNotifier
import ee.carlrobert.codegpt.agent.ToolRunContext
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class BashOutputTool(private val sessionId: String = "global") :
    Tool<BashOutputTool.Args, BashOutputTool.Result>(
        argsSerializer = Args.serializer(),
        resultSerializer = Result.serializer(),
        name = "BashOutput",
        description = """
- Retrieves output from a running or completed background bash shell
- Takes a shell_id parameter identifying the shell
- Always returns only new output since the last check
- Returns stdout and stderr output along with shell status
- Supports optional regex filtering to show only lines matching a pattern
- Use this tool when you need to monitor or check the output of a long-running shell
- Shell IDs can be found using the Bash tool with run_in_background=true
""".trimIndent()
    ) {

    @Serializable
    data class Args(
        @property:LLMDescription(
            "The ID of the background shell to retrieve output from"
        )
        @SerialName("bash_id")
        val bashId: String,
        @property:LLMDescription(
            "Optional regular expression to filter the output lines. Only lines matching this regex will be included in the result."
        )
        val filter: String? = null
    )

    @Serializable
    data class Result(
        @SerialName("bash_id")
        val bashId: String,
        val stdout: String,
        val stderr: String,
        val status: String,
        @SerialName("exit_code")
        val exitCode: Int?
    )

    override suspend fun execute(args: Args): Result = withContext(Dispatchers.IO) {
        val processOutput = BackgroundProcessManager.getOutput(args.bashId)
            ?: return@withContext Result(
                bashId = args.bashId,
                stdout = "",
                stderr = "",
                status = "not_found",
                exitCode = null
            )

        val stdout = processOutput.stdout.toString()
        val stderr = processOutput.stderr.toString()
        val filteredStdout = getFilteredOutput(stdout, args.filter)
        val filteredStderr = getFilteredOutput(stderr, args.filter)

        val toolId = ToolRunContext.getToolId(sessionId)
        if (toolId != null) {
            val publisher = ApplicationManager.getApplication()
                .messageBus
                .syncPublisher(AgentToolOutputNotifier.AGENT_TOOL_OUTPUT_TOPIC)
            if (filteredStdout.isNotEmpty()) {
                filteredStdout.split('\n').forEach { line ->
                    if (line.isNotEmpty()) publisher.toolOutput(toolId, line, false)
                }
            }
            if (filteredStderr.isNotEmpty()) {
                filteredStderr.split('\n').forEach { line ->
                    if (line.isNotEmpty()) publisher.toolOutput(toolId, line, true)
                }
            }
        }

        val status = when {
            processOutput.isComplete -> "completed"
            BackgroundProcessManager.getProcess(args.bashId)?.isAlive == true -> "running"
            else -> "terminated"
        }

        Result(
            bashId = args.bashId,
            stdout = filteredStdout.trimEnd(),
            stderr = filteredStderr.trimEnd(),
            status = status,
            exitCode = processOutput.exitCode
        )
    }

    override fun encodeResultToString(result: Result): String =
        buildString {
            appendLine("Shell ID: ${result.bashId}")
            appendLine("Status: ${result.status}")

            if (result.exitCode != null) {
                appendLine("Exit code: ${result.exitCode}")
            }

            if (result.stdout.isNotEmpty()) {
                appendLine()
                appendLine("STDOUT:")
                appendLine(result.stdout)
            }

            if (result.stderr.isNotEmpty()) {
                appendLine()
                appendLine("STDERR:")
                appendLine(result.stderr)
            }

            if (result.stdout.isEmpty() && result.stderr.isEmpty() && result.status == "running") {
                appendLine()
                appendLine("(No new output since last check)")
            }
        }.trimEnd().truncateToolResult()

    private fun getFilteredOutput(output: String, filter: String?): String {
        return if (filter != null) {
            val regex = Regex(filter)
            output.lines().filter { it.matches(regex) }.joinToString("\n")
        } else {
            output
        }
    }
}
