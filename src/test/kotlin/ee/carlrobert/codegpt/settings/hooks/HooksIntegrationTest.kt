package ee.carlrobert.codegpt.settings.hooks

import ai.koog.agents.ext.tool.shell.ShellCommandConfirmation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.agent.ToolRunContext
import ee.carlrobert.codegpt.agent.tools.BashTool
import ee.carlrobert.codegpt.agent.tools.EditTool
import ee.carlrobert.codegpt.agent.tools.ReadTool
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class HooksIntegrationTest : IntegrationTest() {

    fun testBeforeToolUseHookDeniesToolExecution() {
        val logFile =
            File(project.basePath, ".proxyai/hooks/pretool_deny.log").apply { parentFile.mkdirs() }
        val hookScript = writeHookScript(
            "pretool_deny.sh",
            $$"#!/usr/bin/env sh\npayload=\"$(cat)\"\necho \"$payload\" >> \"$PWD/.proxyai/hooks/pretool_deny.log\"\necho '{\"reason\":\"Denied by preToolUse\"}'\nexit 2\n"
        )
        val settings =
            """{"beforeToolUse":[{"command":".proxyai/hooks/${hookScript.name}"}]}"""
        writeSettings(settings)
        val tool = BashTool(
            project.basePath ?: "",
            { ShellCommandConfirmation.Approved },
            "test-pretool-deny",
            project.service(),
            HookManager(project)
        )
        ToolRunContext.set("test-pretool-deny", "tool-pretool-deny")

        val result = runBlocking {
            tool.execute(BashTool.Args(command = "echo SHOULD_NOT_RUN", description = "test"))
        }

        assertThat(result.exitCode).isNull()
        assertThat(result.output).isEqualTo("Denied by preToolUse")
        val map = ObjectMapper().registerKotlinModule()
            .readValue(logFile.readText(), Map::class.java)
        assertThat(map["hook_event_name"]).isEqualTo("beforeToolUse")
        assertThat(map["tool_name"]).isEqualTo("Bash")
        assertThat(map["cwd"]).isEqualTo(project.basePath)
        assertThat((map["tool_input"] as Map<*, *>)["command"]).isEqualTo("echo SHOULD_NOT_RUN")
    }

    fun testBeforeToolUseHookCanRewriteToolInput() {
        val logFile =
            File(
                project.basePath,
                ".proxyai/hooks/pretool_update.log"
            ).apply { parentFile.mkdirs() }
        val hookScript = writeHookScript(
            "pretool_update.sh",
            $$"#!/usr/bin/env sh\npayload=\"$(cat)\"\necho \"$payload\" >> \"$PWD/.proxyai/hooks/pretool_update.log\"\necho '{\"updated_input\":{\"command\":\"echo REWRITTEN_BY_HOOK\",\"timeout\":60000,\"description\":\"test\",\"run_in_background\":null}}'\nexit 0\n"
        )
        val settings =
            """{"beforeToolUse":[{"command":".proxyai/hooks/${hookScript.name}"}]}"""
        writeSettings(settings)
        val tool = BashTool(
            project.basePath ?: "",
            { ShellCommandConfirmation.Approved },
            "test",
            project.service(),
            HookManager(project)
        )
        ToolRunContext.set("test", "tool-1")

        val result = runBlocking {
            tool.execute(BashTool.Args(command = "echo ORIGINAL", description = "test"))
        }

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.command).isEqualTo("echo REWRITTEN_BY_HOOK")
        assertThat(result.output.trim()).isEqualTo("REWRITTEN_BY_HOOK")
        val map = ObjectMapper().registerKotlinModule()
            .readValue(logFile.readText(), Map::class.java)
        assertThat(map["hook_event_name"]).isEqualTo("beforeToolUse")
        assertThat(map["tool_name"]).isEqualTo("Bash")
        assertThat((map["tool_input"] as Map<*, *>)["command"]).isEqualTo("echo ORIGINAL")
    }

    fun testAfterToolUseHookCanRewriteToolOutput() {
        val logFile =
            File(
                project.basePath,
                ".proxyai/hooks/posttool_update.log"
            ).apply { parentFile.mkdirs() }
        val hookScript = writeHookScript(
            "posttool_update.sh",
            $$"""#!/usr/bin/env sh
payload="$(cat)"
echo "$payload" >> "$PWD/.proxyai/hooks/posttool_update.log"
echo '{"updated_output":{"command":"echo ORIGINAL","exitCode":0,"output":"REWRITTEN_BY_AFTER_HOOK","bashId":null}}'
exit 0
"""
        )
        val settings =
            """{"afterToolUse":[{"command":".proxyai/hooks/${hookScript.name}"}]}"""
        writeSettings(settings)
        val tool = BashTool(
            project.basePath ?: "",
            { ShellCommandConfirmation.Approved },
            "test",
            project.service(),
            HookManager(project)
        )
        ToolRunContext.set("test", "tool-1")

        val result = runBlocking {
            tool.execute(BashTool.Args(command = "echo ORIGINAL", description = "test"))
        }

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.command).isEqualTo("echo ORIGINAL")
        assertThat(result.output).isEqualTo("REWRITTEN_BY_AFTER_HOOK")
        val map = ObjectMapper().registerKotlinModule()
            .readValue(logFile.readText(), Map::class.java)
        assertThat(map["hook_event_name"]).isEqualTo("afterToolUse")
        assertThat(map["tool_name"]).isEqualTo("Bash")
        assertThat((map["tool_output"] as Map<*, *>)["output"]).isEqualTo("ORIGINAL")
    }

    fun testBeforeShellExecutionHookDeniesCommand() {
        val logFile =
            File(project.basePath, ".proxyai/hooks/deny.log").apply { parentFile.mkdirs() }
        val hookScript = writeHookScript(
            "deny.sh",
            $$"#!/usr/bin/env sh\npayload=\"$(cat)\"\necho \"$payload\" >> \"$PWD/.proxyai/hooks/deny.log\"\necho '{\"reason\":\"Denied by hook\"}'\nexit 2\n"
        )
        val settings =
            """{"beforeShellExecution":[{"command":".proxyai/hooks/${hookScript.name}"}]}"""
        writeSettings(settings)
        val tool = BashTool(
            project.basePath ?: "",
            { ShellCommandConfirmation.Approved },
            "test",
            project.service(),
            HookManager(project)
        )
        ToolRunContext.set("test", "tool-1")

        val result = runBlocking { tool.execute(BashTool.Args(command = "echo test")) }

        assertThat(result.exitCode).isNull()
        assertThat(result.output).isEqualTo("Denied by hook")
        val map = ObjectMapper().registerKotlinModule()
            .readValue(logFile.readText(), Map::class.java)
        assertThat(map["hook_event_name"]).isEqualTo("beforeShellExecution")
        assertThat(map["command"]).isEqualTo("echo test")
    }

    fun testAfterFileEditHookWritesLog() {
        val target = File(project.basePath, "hook_edit.txt").apply {
            parentFile.mkdirs()
            writeText("hello")
        }
        val logFile =
            File(project.basePath, ".proxyai/hooks/edit.log").apply {
                parentFile.mkdirs()
            }
        val hookScript = writeHookScript(
            "edit.sh",
            $$"#!/usr/bin/env sh\npayload=\"$(cat)\"\necho \"$PROXYAI_HOOK_EVENT|$payload\" >> \"$PWD/.proxyai/hooks/edit.log\"\nexit 0\n"
        )
        val settings =
            """{"afterFileEdit":[{"command":".proxyai/hooks/${hookScript.name}"}]}"""
        writeSettings(settings)

        val result = runBlocking {
            EditTool(project, HookManager(project), "test")
                .execute(
                    EditTool.Args(
                        target.absolutePath,
                        "hello",
                        "world",
                        "replace",
                        false
                    )
                )
        }

        assertThat(result).isInstanceOf(EditTool.Result.Success::class.java)
        val logContent = logFile.takeIf { it.exists() }?.readText().orEmpty()
        assertThat(logContent).contains("afterFileEdit").contains(target.absolutePath)
    }

    fun testBeforeReadFileHookDeniesRead() {
        val target = File(project.basePath, "hook_read.txt").apply {
            parentFile.mkdirs()
            writeText("secret")
        }
        val hookScript = writeHookScript(
            "read.sh",
            "#!/usr/bin/env sh\ncat >/dev/null\necho '{\"reason\":\"Read blocked\"}'\nexit 2\n"
        )
        val settings =
            """{"beforeReadFile":[{"command":".proxyai/hooks/${hookScript.name}"}]}"""
        writeSettings(settings)

        val result = runBlocking {
            ReadTool(project, HookManager(project), "test")
                .execute(ReadTool.Args(target.absolutePath))
        }

        val isError = result is ReadTool.Result.Error
        val error = (result as? ReadTool.Result.Error)?.error.orEmpty()
        assertThat(isError).isTrue()
        assertThat(error).isEqualTo("Read blocked")
    }

    fun testAfterFileEditHookWithSimpleLogging() {
        val target = File(project.basePath, "hook_simple.txt").apply {
            parentFile.mkdirs()
            writeText("hello")
        }
        val logFile =
            File(project.basePath, ".proxyai/hooks/simple.log").apply { parentFile.mkdirs() }
        val hookScript = writeHookScript(
            "simple.sh",
            $$"#!/usr/bin/env sh\npayload=\"$(cat)\"\necho \"$payload\" >> \"$PWD/.proxyai/hooks/simple.log\"\nexit 0\n"
        )
        val settings = """{"afterFileEdit":[{"command":".proxyai/hooks/${hookScript.name}"}]}"""
        writeSettings(settings)

        val result = runBlocking {
            EditTool(project, HookManager(project), "test")
                .execute(
                    EditTool.Args(
                        target.absolutePath,
                        "hello",
                        "world",
                        "replace",
                        false
                    )
                )
        }

        assertThat(result).isInstanceOf(EditTool.Result.Success::class.java)
        assertThat(target.readText()).isEqualTo("world")
        assertThat(logFile.exists()).isTrue()
        val logContent = logFile.readText()
        val map = ObjectMapper().registerKotlinModule()
            .readValue(logContent, Map::class.java)
        assertThat(map["hook_event_name"]).isEqualTo("afterFileEdit")
        assertThat(map["file_path"]).isEqualTo(target.absolutePath)
        assertThat(map["replacements_made"]).isEqualTo(1)
    }

    private fun writeHookScript(name: String, content: String): File {
        val file = File(project.basePath, ".proxyai/hooks/$name")
        file.parentFile.mkdirs()
        file.writeText(content)
        file.setExecutable(true)
        return file
    }

    private fun writeSettings(content: String): File {
        val file = File(project.basePath, ".proxyai/settings.json")
        file.parentFile.mkdirs()
        file.writeText("""{"ignore":[],"permissions":{"allow":[]},"hooks":$content}""")
        Files.setLastModifiedTime(
            file.toPath(),
            FileTime.fromMillis(System.currentTimeMillis() + 1000)
        )
        return file
    }
}
