package ee.carlrobert.codegpt.agent

import ee.carlrobert.codegpt.agent.tools.DiagnosticsTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.io.File

class DiagnosticsToolTest : IntegrationTest() {

    fun `test diagnostics tool should be registered in tool specs`() {
        assertThat(ToolSpecs.find("Diagnostics")).isNotNull()
    }

    fun `test diagnostics tool should return missing file error`() {
        val missingPath = File(project.basePath, "does-not-exist.txt").absolutePath
        val tool = DiagnosticsTool(project, "test-session-id", HookManager(project))

        val result = runBlocking {
            tool.execute(DiagnosticsTool.Args(missingPath))
        }

        assertThat(result.error).isEqualTo("File not found: $missingPath")
    }
}
