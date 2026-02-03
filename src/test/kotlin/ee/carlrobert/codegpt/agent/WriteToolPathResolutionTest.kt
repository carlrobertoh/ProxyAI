package ee.carlrobert.codegpt.agent

import ee.carlrobert.codegpt.agent.tools.WriteTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.io.File

class WriteToolPathResolutionTest : IntegrationTest() {

    fun testWriteCreatesParentDirectoriesForNewFile() {
        val target = File(project.basePath, "app/components/NewLanding.tsx")

        val result = runBlocking {
            WriteTool(project, HookManager(project))
                .execute(
                    WriteTool.Args(
                        target.absolutePath,
                        "export default function NewLanding() {}"
                    )
                )
        }

        assertThat(result).isInstanceOf(WriteTool.Result.Success::class.java)
        assertThat(target.exists()).isTrue()
    }

    fun testWriteResolvesLikelyProjectRelativeAbsolutePath() {
        val pseudoRootDir = "__proxyai_write_test__"
        val target = File(project.basePath, "$pseudoRootDir/components/NewLanding.tsx")
        target.parentFile.mkdirs()

        val result = runBlocking {
            WriteTool(project, HookManager(project))
                .execute(
                    WriteTool.Args(
                        "/$pseudoRootDir/components/NewLanding.tsx",
                        "export default function NewLanding() {}"
                    )
                )
        }

        assertThat(result).isInstanceOf(WriteTool.Result.Success::class.java)
        val success = result as WriteTool.Result.Success
        assertThat(success.filePath).isEqualTo(target.absolutePath)
        assertThat(target.readText()).contains("NewLanding")
    }
}
