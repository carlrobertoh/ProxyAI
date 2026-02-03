package ee.carlrobert.codegpt.agent

import ai.koog.agents.ext.tool.shell.ShellCommandConfirmation
import ee.carlrobert.codegpt.agent.tools.BashTool
import ee.carlrobert.codegpt.agent.tools.ReadTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class PermissionsTest : IntegrationTest() {

    fun testReadDeniedByDenyRuleMatchingFileName() {
        val file = File(project.basePath, "blocked-read.txt").apply { writeText("secret") }
        writeSettings(
            denyEntries = listOf("Read(blocked-read.txt)")
        )

        val result = runBlocking {
            seedToolContext("perm-test")
            ReadTool(project, HookManager(project), "perm-test")
                .execute(ReadTool.Args(file.absolutePath))
        }

        assertThat(result).isInstanceOf(ReadTool.Result.Error::class.java)
        val error = result as ReadTool.Result.Error
        assertThat(error.error).isEqualTo("Access denied by permissions.deny for Read")
    }

    fun testReadDeniedWhenAllowRulesExistAndNoneMatch() {
        val file = File(project.basePath, "docs/notes.txt").apply {
            parentFile.mkdirs()
            writeText("notes")
        }
        writeSettings(
            allowEntries = listOf("Read(./src/**)")
        )

        val result = runBlocking {
            seedToolContext("perm-test")
            ReadTool(project, HookManager(project), "perm-test")
                .execute(ReadTool.Args(file.absolutePath))
        }

        assertThat(result).isInstanceOf(ReadTool.Result.Error::class.java)
        val error = result as ReadTool.Result.Error
        assertThat(error.error).isEqualTo("Access denied by permissions.allow for Read")
    }

    fun testReadAllowedWhenAllowRuleMatchesPath() {
        val file = File(project.basePath, "src/main/kotlin/allowed.txt").apply {
            parentFile.mkdirs()
            writeText("ok")
        }
        writeSettings(
            allowEntries = listOf("Read(./src/**)")
        )

        val result = runBlocking {
            seedToolContext("perm-test")
            ReadTool(project, HookManager(project), "perm-test")
                .execute(ReadTool.Args(file.absolutePath))
        }

        assertThat(result).isInstanceOf(ReadTool.Result.Success::class.java)
    }

    fun testBashDeniedByDenyRule() {
        writeSettings(
            denyEntries = listOf("Bash(git push *)")
        )

        val tool = createBashTool("perm-bash") { ShellCommandConfirmation.Approved }
        val result = runBlocking {
            seedToolContext("perm-bash")
            tool.execute(BashTool.Args(command = "git push origin main", description = "push"))
        }

        assertThat(result.exitCode).isNull()
        assertThat(result.output).isEqualTo("Access denied by permissions.deny for Bash")
    }

    fun testBashAllowRuleBypassesConfirmationHandler() {
        writeSettings(
            allowEntries = listOf("Bash(echo *)")
        )

        val tool = createBashTool("perm-bash-allow") {
            ShellCommandConfirmation.Denied("should not be called when allow matches")
        }
        val result = runBlocking {
            seedToolContext("perm-bash-allow")
            tool.execute(BashTool.Args(command = "echo OK", description = "echo"))
        }

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.output.trim()).isEqualTo("OK")
    }

    fun testBashFallsBackToConfirmationWhenNoAllowMatch() {
        writeSettings(
            allowEntries = listOf("Bash(echo *)")
        )

        val tool = createBashTool("perm-bash-deny") {
            ShellCommandConfirmation.Denied("explicit deny")
        }
        val result = runBlocking {
            seedToolContext("perm-bash-deny")
            tool.execute(BashTool.Args(command = "git status", description = "status"))
        }

        assertThat(result.exitCode).isNull()
        assertThat(result.output).contains("explicit deny")
    }

    private fun createBashTool(
        sessionId: String,
        confirmation: suspend (BashTool.Args) -> ShellCommandConfirmation
    ): BashTool {
        return BashTool(
            project = project,
            confirmationHandler = confirmation,
            sessionId = sessionId,
            hookManager = HookManager(project)
        )
    }

    private fun writeSettings(
        allowEntries: List<String> = emptyList(),
        askEntries: List<String> = emptyList(),
        denyEntries: List<String> = emptyList()
    ): File {
        val allowJson = allowEntries.joinToString(",") { "\"$it\"" }
        val askJson = askEntries.joinToString(",") { "\"$it\"" }
        val denyJson = denyEntries.joinToString(",") { "\"$it\"" }
        val file = File(project.basePath, ".proxyai/settings.json")
        file.parentFile.mkdirs()
        file.writeText("""{"ignore":[],"permissions":{"allow":[$allowJson],"ask":[$askJson],"deny":[$denyJson]},"hooks":{}}""")
        Files.setLastModifiedTime(
            file.toPath(),
            FileTime.fromMillis(System.currentTimeMillis() + 1000)
        )
        return file
    }

    private fun seedToolContext(sessionId: String) {
        ToolRunContext.set(
            sessionId = sessionId,
            toolId = "test-tool-$sessionId"
        )
    }
}
