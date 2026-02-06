package ee.carlrobert.codegpt.agent

import ai.koog.agents.ext.tool.shell.ShellCommandConfirmation
import ee.carlrobert.codegpt.agent.tools.BashTool
import ee.carlrobert.codegpt.agent.tools.ReadTool
import ee.carlrobert.codegpt.agent.tools.WriteTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class IgnoreRulesTest : IntegrationTest() {

    fun testReadBlockedByIgnoreRule() {
        val envFile = File(project.basePath, ".env").apply { writeText("SECRET=1") }
        writeSettings(ignoreEntries = listOf(".env"))

        val result = runBlocking {
            ReadTool(project, HookManager(project), "ignore-test")
                .execute(ReadTool.Args(envFile.absolutePath))
        }

        assertThat(result).isInstanceOf(ReadTool.Result.Error::class.java)
        val error = result as ReadTool.Result.Error
        assertThat(error.error).isEqualTo("File not found: ${envFile.absolutePath}")
    }

    fun testWriteBlockedByIgnoreRule() {
        val pemFile = File(project.basePath, "certs/private.pem")
        writeSettings(ignoreEntries = listOf("**/*.pem"))

        val result = runBlocking {
            WriteTool(project, HookManager(project))
                .execute(WriteTool.Args(pemFile.absolutePath, "PRIVATE KEY"))
        }

        assertThat(result).isInstanceOf(WriteTool.Result.Error::class.java)
        val error = result as WriteTool.Result.Error
        assertThat(error.error).isEqualTo("File not found: ${pemFile.absolutePath}")
    }

    fun testBashBlockedByIgnoreRule() {
        val secretFile = File(project.basePath, "secrets/token.txt").apply {
            parentFile.mkdirs()
            writeText("token")
        }
        writeSettings(ignoreEntries = listOf("secrets/**"))

        val tool = BashTool(
            project = project,
            confirmationHandler = { ShellCommandConfirmation.Approved },
            sessionId = "ignore-bash",
            hookManager = HookManager(project)
        )
        val result = runBlocking {
            tool.execute(
                BashTool.Args(
                    command = "cat ${secretFile.absolutePath}",
                    description = "cat"
                )
            )
        }

        assertThat(result.exitCode).isNull()
        assertThat(result.output).isEqualTo("Command denied by policy: access to ignored files is blocked")
    }

    fun testReadAllowedWhenPathNotIgnored() {
        val file = File(project.basePath, "src/ok.txt").apply {
            parentFile.mkdirs()
            writeText("ok")
        }
        writeSettings(ignoreEntries = listOf("secrets/**"))

        val result = runBlocking {
            ReadTool(project, HookManager(project), "ignore-test")
                .execute(ReadTool.Args(file.absolutePath))
        }

        assertThat(result).isInstanceOf(ReadTool.Result.Success::class.java)
    }

    fun testReadBlockedWithIgnoreOnlySettingsFile() {
        val file = File(project.basePath, "app/src/main/Test.kt").apply {
            parentFile.mkdirs()
            writeText("secret")
        }
        writeSettingsRaw("""{"ignore":["app/src/main/**"]}""")

        val result = runBlocking {
            ReadTool(project, HookManager(project), "ignore-test")
                .execute(ReadTool.Args(file.absolutePath))
        }

        assertThat(result).isInstanceOf(ReadTool.Result.Error::class.java)
        val error = result as ReadTool.Result.Error
        assertThat(error.error).isEqualTo("File not found: ${file.absolutePath}")
    }

    fun testReadBlockedByNestedBuildDirectoryRule() {
        val file = File(project.basePath, "app/build/reports/test.txt").apply {
            parentFile.mkdirs()
            writeText("secret")
        }
        writeSettings(ignoreEntries = listOf("build/"))

        val result = runBlocking {
            ReadTool(project, HookManager(project), "ignore-test")
                .execute(ReadTool.Args(file.absolutePath))
        }

        assertThat(result).isInstanceOf(ReadTool.Result.Error::class.java)
        val error = result as ReadTool.Result.Error
        assertThat(error.error).isEqualTo("File not found: ${file.absolutePath}")
    }

    fun testWriteBlockedByNestedExtensionRule() {
        val file = File(project.basePath, "app/certs/private.pem")
        writeSettings(ignoreEntries = listOf("*.pem"))

        val result = runBlocking {
            WriteTool(project, HookManager(project))
                .execute(WriteTool.Args(file.absolutePath, "PRIVATE KEY"))
        }

        assertThat(result).isInstanceOf(WriteTool.Result.Error::class.java)
        val error = result as WriteTool.Result.Error
        assertThat(error.error).isEqualTo("File not found: ${file.absolutePath}")
    }

    private fun writeSettings(ignoreEntries: List<String>): File {
        val ignoreJson = ignoreEntries.joinToString(",") { "\"$it\"" }
        val file = File(project.basePath, ".proxyai/settings.json")
        file.parentFile.mkdirs()
        file.writeText("""{"ignore":[$ignoreJson],"permissions":{"allow":[],"ask":[],"deny":[]},"hooks":{}}""")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 1000))
        return file
    }

    private fun writeSettingsRaw(rawJson: String): File {
        val file = File(project.basePath, ".proxyai/settings.json")
        file.parentFile.mkdirs()
        file.writeText(rawJson)
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 1000))
        return file
    }
}
