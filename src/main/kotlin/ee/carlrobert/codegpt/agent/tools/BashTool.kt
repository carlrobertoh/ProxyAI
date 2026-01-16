package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.ext.tool.shell.ShellCommandConfirmation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.VirtualFileManager
import ee.carlrobert.codegpt.agent.AgentToolOutputNotifier
import ee.carlrobert.codegpt.agent.ToolRunContext
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

fun interface BashCommandConfirmationHandler {
    suspend fun requestConfirmation(args: BashTool.Args): ShellCommandConfirmation
}

class BashTool(
    private val workingDirectory: String,
    private val confirmationHandler: BashCommandConfirmationHandler,
    private val sessionId: String = "global",
    private val settingsService: ProxyAISettingsService? = null
) : Tool<BashTool.Args, BashTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "Bash",
    description = $$"""
    Executes a given bash command in a persistent shell session with optional timeout, ensuring proper handling and security measures.
    
    IMPORTANT: This tool is for terminal operations like git, npm, docker, etc. DO NOT use it for file operations (reading, writing, editing, searching, finding files) - use the specialized tools for this instead.
    
    <env>
    Working directory: $$workingDirectory
    </env>
    
    Before executing the command, please follow these steps:
    
    1. Directory Verification:
       - If the command will create new directories or files, first use `ls` to verify the parent directory exists and is the correct location
       - For example, before running "mkdir foo/bar", first use `ls foo` to check that "foo" exists and is the intended parent directory
    
    2. Command Execution:
       - Always quote file paths that contain spaces with double quotes (e.g., cd "path with spaces/file.txt")
       - Examples of proper quoting:
         - cd "/Users/name/My Documents" (correct)
         - cd /Users/name/My Documents (incorrect - will fail)
         - python "/path/with spaces/script.py" (correct)
         - python /path/with spaces/script.py (incorrect - will fail)
       - After ensuring proper quoting, execute the command.
       - Capture the output of the command.
    
    Usage notes:
      - The command argument is required.
      - You can specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). If not specified, commands will timeout after 120000ms (2 minutes).
      - It is very helpful if you write a clear, concise description of what this command does in 5-10 words.
      - If the output exceeds 30000 characters, output will be truncated before being returned to you.
      - You can use the `run_in_background` parameter to run the command in the background, which allows you to continue working while the command runs. You can monitor the output using the Bash tool as it becomes available. You do not need to use '&' at the end of the command when using this parameter.
      
      - Avoid using Bash with the `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, or `echo` commands, unless explicitly instructed or when these commands are truly necessary for the task. Instead, always prefer using the dedicated tools for these commands:
        - Content search: Use Grep (NOT grep or rg)
        - Read files: Use Read (NOT cat/head/tail)
        - Edit files: Use Edit (NOT sed/awk)
        - Write files: Use Write (NOT echo >/cat <<EOF)
        - Communication: Output text directly (NOT echo/printf)
      - When issuing multiple commands:
        - If the commands are independent and can run in parallel, make multiple Bash tool calls in a single message. For example, if you need to run "git status" and "git diff", send a single message with two Bash tool calls in parallel.
        - If the commands depend on each other and must run sequentially, use a single Bash call with '&&' to chain them together (e.g., `git add . && git commit -m "message" && git push`). For instance, if one operation must complete before another starts (like mkdir before cp, Write before Bash for git operations, or git add before git commit), run these operations sequentially instead.
        - Use ';' only when you need to run commands sequentially but don't care if earlier commands fail
        - DO NOT use newlines to separate commands (newlines are ok in quoted strings)
      - Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of `cd`. You may use `cd` if the User explicitly requests it.
        <good-example>
        pytest /foo/bar/tests
        </good-example>
        <bad-example>
        cd /foo/bar && pytest tests
        </bad-example>
    
    # Committing changes with git
    
    Only create commits when requested by the user. If unclear, ask first. When the user asks you to create a new git commit, follow these steps carefully:
    
    Git Safety Protocol:
    - NEVER update the git config
    - NEVER run destructive/irreversible git commands (like push --force, hard reset, etc) unless the user explicitly requests them 
    - NEVER skip hooks (--no-verify, --no-gpg-sign, etc) unless the user explicitly requests it
    - NEVER run force push to main/master, warn the user if they request it
    - Avoid git commit --amend.  ONLY use --amend when either (1) user explicitly requested amend OR (2) adding edits from pre-commit hook (additional instructions below) 
    - Before amending: ALWAYS check authorship (git log -1 --format='%an %ae')
    - NEVER commit changes unless the user explicitly asks you to. It is VERY IMPORTANT to only commit when explicitly asked, otherwise the user will feel that you are being too proactive.
    
    1. You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. run the following bash commands in parallel, each using the Bash tool:
      - Run a git status command to see all untracked files.
      - Run a git diff command to see both staged and unstaged changes that will be committed.
      - Run a git log command to see recent commit messages, so that you can follow this repository's commit message style.
    2. Analyze all staged changes (both previously staged and newly added) and draft a commit message:
      - Summarize the nature of the changes (eg. new feature, enhancement to an existing feature, bug fix, refactoring, test, docs, etc.). Ensure the message accurately reflects the changes and their purpose (i.e. "add" means a wholly new feature, "update" means an enhancement to an existing feature, "fix" means a bug fix, etc.).
      - Do not commit files that likely contain secrets (.env, credentials.json, etc). Warn the user if they specifically request to commit those files
      - Draft a concise (1-2 sentences) commit message that focuses on the "why" rather than the "what"
      - Ensure it accurately reflects the changes and their purpose
    3. You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. run the following commands:
       - Add relevant untracked files to the staging area.
       - Run git status after the commit completes to verify success.
       Note: git status depends on the commit completing, so run it sequentially after the commit.
    4. If the commit fails due to pre-commit hook changes, retry ONCE. If it succeeds but files were modified by the hook, verify it's safe to amend:
       - Check HEAD commit: git log -1 --format='[%h] (%an <%ae>) %s'. VERIFY it matches your commit
       - Check not pushed: git status shows "Your branch is ahead"
       - If both true: amend your commit. Otherwise: create NEW commit (never amend other developers' commits)
    
    Important notes:
    - NEVER run additional commands to read or explore code, besides git bash commands
    - NEVER use the TodoWrite or Task tools
    - DO NOT push to the remote repository unless the user explicitly asks you to do so
    - IMPORTANT: Never use git commands with the -i flag (like git rebase -i or git add -i) since they require interactive input which is not supported.
    - If there are no changes to commit (i.e., no untracked files and no modifications), do not create an empty commit
    - In order to ensure good formatting, ALWAYS pass the commit message via a HEREDOC, a la this example:
    <example>
    git commit -m "$(cat <<'EOF'
       Commit message here.
       EOF
       )"
    </example>
    
    # Creating pull requests
    Use the gh command via the Bash tool for ALL GitHub-related tasks including working with issues, pull requests, checks, and releases. If given a Github URL use the gh command to get the information needed.
    
    IMPORTANT: When the user asks you to create a pull request, follow these steps carefully:
    
    1. You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. Run the following bash commands in parallel using the Bash tool, in order to understand the current state of the branch since it diverged from the main branch:
       - Run a git status command to see all untracked files
       - Run a git diff command to see both staged and unstaged changes that will be committed
       - Check if the current branch tracks a remote branch and is up to date with the remote, so you know if you need to push to the remote
       - Run a git log command and `git diff [base-branch]...HEAD` to understand the full commit history for the current branch (from the time it diverged from the base branch)
    2. Analyze all changes that will be included in the pull request, making sure to look at all relevant commits (NOT just the latest commit, but ALL commits that will be included in the pull request!!!), and draft a pull request summary
    3. You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. run the following commands in parallel:
       - Create new branch if needed
       - Push to remote with -u flag if needed
       - Create PR using gh pr create with the format below. Use a HEREDOC to pass the body to ensure correct formatting.
    <example>
    gh pr create --title "the pr title" --body "$(cat <<'EOF'
    ## Summary
    <1-3 bullet points>
    
    ## Test plan
    [Bulleted markdown checklist of TODOs for testing the pull request...]
    
    🤖 Generated with [ProxyAI](https://tryproxy.io/)
    EOF
    )"
    </example>
    
    Important:
    - DO NOT use the TodoWrite or Task tools
    - Return the PR URL when you're done, so the user can see it
    
    # Other common operations
    - View comments on a Github PR: gh api repos/foo/bar/pulls/123/comments
""".trimIndent()
) {

    @Serializable
    data class Args(
        @property:LLMDescription(
            "The command to execute"
        )
        val command: String,
        @property:LLMDescription(
            "Optional timeout in milliseconds (max 600000)"
        )
        val timeout: Int = 60_000,
        @property:LLMDescription(
            "Clear, concise description of what this command does in 5-10 words, in active voice. Examples:\n" +
                    "Input: ls\n" +
                    "Output: List files in current directory\n\n" +
                    "Input: git status\n" +
                    "Output: Show working tree status\n\n" +
                    "Input: npm install\n" +
                    "Output: Install package dependencies\n\n" +
                    "Input: mkdir foo\n" +
                    "Output: Create directory 'foo'"
        )
        val description: String? = null,
        @property:LLMDescription(
            "Set to true to run this command in the background. Use BashOutput to read the output later."
        )
        @SerialName("run_in_background")
        val runInBackground: Boolean? = null,
    )

    @Serializable
    data class Result(
        val command: String,
        val exitCode: Int?,
        val output: String,
        @SerialName("bash_id")
        val bashId: String? = null
    )

    private fun isWhiteListed(args: Args): Boolean {
        val permissions = settingsService?.getBashPermissions() ?: emptyList()
        return permissions.any { pattern ->
            when {
                pattern.startsWith("Bash(") && pattern.endsWith(":*)") -> {
                    val commandPattern = pattern.removePrefix("Bash(").removeSuffix(":*)")
                    args.command.startsWith(commandPattern)
                }

                pattern.startsWith("Bash(") && pattern.endsWith(")") -> {
                    val commandPattern = pattern.removePrefix("Bash(").removeSuffix(")")
                    args.command == commandPattern
                }

                else -> false
            }
        }
    }

    override suspend fun execute(args: Args): Result {
        if (shouldBlockByIgnore(args.command)) {
            return Result(
                args.command,
                null,
                "Command denied by policy: access to ignored files is blocked",
                null
            )
        }
        val isAskPattern = shouldAskForConfirmation(args.command)
        val toolId = ToolRunContext.getToolId(sessionId)
            ?: throw IllegalArgumentException("Tool ID is missing")

        val confirmation = when {
            isWhiteListed(args) && !isAskPattern -> ShellCommandConfirmation.Approved
            else -> confirmationHandler.requestConfirmation(args)
        }
        return when (confirmation) {
            is ShellCommandConfirmation.Approved -> {
                try {
                    if (args.runInBackground == true) {
                        val bashId = executeBackground(args.command)
                        Result(
                            args.command,
                            null,
                            "Background process started with ID: $bashId",
                            bashId
                        )
                    } else {
                        runForegroundWithStreaming(toolId, args)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result(args.command, null, "Failed to execute command: ${e.message}", null)
                }
            }

            is ShellCommandConfirmation.Denied -> {
                Result(
                    args.command,
                    null,
                    "Command execution denied with user response: ${confirmation.userResponse}",
                    null
                )
            }
        }
    }

    private suspend fun runForegroundWithStreaming(toolId: String, args: Args): Result {
        val publisher = ApplicationManager.getApplication().messageBus
            .syncPublisher(AgentToolOutputNotifier.AGENT_TOOL_OUTPUT_TOPIC)

        return withContext(Dispatchers.IO) {
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()
            val activityChannel = Channel<Unit>(Channel.CONFLATED)

            val shellCommand = buildShellCommand(args.command)
            val process = ProcessBuilder(shellCommand)
                .apply {
                    directory(java.io.File(workingDirectory))
                    redirectErrorStream(false)
                }
                .start()
            closeStdin(process)

            try {
                val job = currentCoroutineContext()[Job]
                job?.invokeOnCompletion { cause ->
                    if (cause is CancellationException && process.isAlive) {
                        terminateProcess(process)
                    }
                }
                val stdoutJob = launch {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                stdoutBuilder.appendLine(line)
                                publisher.toolOutput(toolId, line, false)
                                activityChannel.trySend(Unit)
                            }
                        }
                    } catch (_: IOException) {
                        // Ignore IO exception if the stream is closed
                    }
                }

                val stderrJob = launch {
                    try {
                        process.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                stderrBuilder.appendLine(line)
                                publisher.toolOutput(toolId, line, true)
                                activityChannel.trySend(Unit)
                            }
                        }
                    } catch (_: IOException) {
                        // Ignore IO exception if the stream is closed
                    }
                }

                val timedOut = AtomicBoolean(false)
                val exitDeferred = async(Dispatchers.IO) { process.waitFor() }
                waitForExitOrIdleTimeout(
                    process = process,
                    timeoutMs = args.timeout,
                    activityChannel = activityChannel,
                    timedOut = timedOut,
                    exitDeferred = exitDeferred
                )

                stdoutJob.join()
                stderrJob.join()

                val combinedOutput = buildCombinedOutput(
                    stdoutBuilder.toString().trimEnd(),
                    stderrBuilder.toString().trimEnd(),
                    if (timedOut.get()) "Command timed out after ${args.timeout}ms of inactivity" else null
                )

                runInEdt(ModalityState.defaultModalityState()) {
                    runWriteAction {
                        VirtualFileManager.getInstance().syncRefresh()
                    }
                }

                Result(
                    args.command,
                    if (!timedOut.get()) process.exitValue() else null,
                    combinedOutput,
                    null
                )
            } finally {
                if (process.isAlive) {
                    terminateProcess(process)
                }
            }
        }
    }

    private fun buildCombinedOutput(
        stdout: String,
        stderr: String,
        message: String? = null
    ): String {
        return buildString {
            if (stdout.isNotEmpty()) appendLine(stdout)
            if (stderr.isNotEmpty()) appendLine(stderr)
            message?.let { appendLine(it) }
        }.trimEnd()
    }

    private suspend fun waitForExitOrIdleTimeout(
        process: Process,
        timeoutMs: Int,
        activityChannel: ReceiveChannel<Unit>,
        timedOut: AtomicBoolean,
        exitDeferred: Deferred<Int>
    ) {
        var done = false
        while (!done) {
            val event = withTimeoutOrNull(timeoutMs.toLong()) {
                select {
                    exitDeferred.onAwait { WaitEvent.EXIT }
                    activityChannel.onReceive { WaitEvent.ACTIVITY }
                }
            }
            when (event) {
                WaitEvent.EXIT -> done = true
                WaitEvent.ACTIVITY -> { /* reset idle timer */
                }

                null -> {
                    timedOut.set(true)
                    terminateProcess(process)
                    done = true
                }
            }
        }
    }

    private enum class WaitEvent {
        EXIT,
        ACTIVITY
    }

    private fun destroyProcessTree(process: Process) {
        val handle = process.toHandle()
        handle.descendants().forEach { child ->
            runCatching { child.destroyForcibly() }
        }
        runCatching { handle.destroyForcibly() }
    }

    private fun closeProcessStreams(process: Process) {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }

    private fun closeStdin(process: Process) {
        runCatching { process.outputStream.close() }
    }

    private fun terminateProcess(process: Process) {
        destroyProcessTree(process)
        closeProcessStreams(process)
    }

    override fun encodeResultToString(result: Result): String = with(result) {
        val raw = buildString {
            appendLine("Command: $command")
            if (output.isNotEmpty()) {
                appendLine(output)
            } else if (exitCode != null) {
                appendLine("(no output)")
            }
            exitCode?.let {
                appendLine("Exit code: $it")
            }
        }.trimEnd()
        raw.truncateToolResult()
    }

    private fun executeBackground(command: String): String {
        val bashId = UUID.randomUUID().toString()
        val shellCommand = buildShellCommand(command)
        val process = ProcessBuilder(shellCommand)
            .apply {
                directory(java.io.File(workingDirectory))
                redirectErrorStream(false)
            }
            .start()

        BackgroundProcessManager.registerProcess(bashId, process)
        return bashId
    }

    private fun buildShellCommand(command: String): List<String> {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        return if (isWindows) {
            listOf("cmd", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }
    }

    private fun shouldAskForConfirmation(command: String): Boolean {
        val askPatterns = listOf(
            "find * -delete*",
            "find * -exec*",
            "find * -fprint*",
            "find * -fls*",
            "find * -fprintf*",
            "find * -ok*",
            "sort --output=*",
            "sort -o *",
            "tree -o *"
        )

        return askPatterns.any { pattern ->
            if (pattern.contains("*")) {
                command.startsWith(pattern.removeSuffix("*"))
            } else {
                command == pattern
            }
        }
    }

    private fun shouldBlockByIgnore(command: String): Boolean {
        val svc = settingsService ?: return false
        val readers = setOf(
            "cat",
            "grep",
            "rg",
            "sed",
            "awk",
            "head",
            "tail",
            "less",
            "more",
            "wc",
            "stat",
            "file"
        )
        val tokens = tokenize(command)
        val paths = mutableListOf<String>()
        var lastWasReader = false
        var expectFileArg = false
        tokens.forEach { t ->
            if (t.startsWith("-")) {
                if (t == "-f" || t.startsWith("--file=")) {
                    expectFileArg = true
                    val eq = t.indexOf('=')
                    if (eq > 0 && eq < t.length - 1) paths.add(t.substring(eq + 1))
                }
                return@forEach
            }
            if (expectFileArg) {
                paths.add(t)
                expectFileArg = false
                return@forEach
            }
            val redirIdx = maxOf(t.lastIndexOf('>'), t.lastIndexOf('<'))
            if (redirIdx >= 0 && redirIdx < t.length - 1) {
                val target = t.substring(redirIdx + 1)
                if (target.isNotBlank()) paths.add(target)
            }
            val tokenIsReader = readers.contains(t)
            if (lastWasReader && !tokenIsReader) paths.add(t)
            if (!tokenIsReader && looksLikePath(t)) paths.add(t)
            lastWasReader = tokenIsReader
        }
        return paths.any { candidate -> svc.isPathIgnored(toAbsolute(candidate)) }
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < s.length) {
            val c = s[i]
            if (quote == null) {
                if (c == '\'' || c == '"') {
                    quote = c
                } else if (c.isWhitespace()) {
                    if (sb.isNotEmpty()) {
                        out.add(sb.toString()); sb.setLength(0)
                    }
                } else {
                    sb.append(c)
                }
            } else {
                if (c == quote) quote = null else sb.append(c)
            }
            i += 1
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun looksLikePath(token: String): Boolean =
        token.startsWith("/") || token.startsWith("./") || token.startsWith("../") || token.contains(
            '/'
        )

    private fun toAbsolute(token: String): String {
        return try {
            val p = Paths.get(token)
            val abs = if (p.isAbsolute) p else Paths.get(workingDirectory).resolve(p)
            abs.normalize().toString()
        } catch (_: Exception) {
            token
        }
    }
}
