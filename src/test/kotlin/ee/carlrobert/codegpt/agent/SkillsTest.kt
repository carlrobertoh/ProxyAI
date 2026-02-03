package ee.carlrobert.codegpt.agent

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.agent.tools.ConfirmingLoadSkillTool
import ee.carlrobert.codegpt.agent.tools.LoadSkillTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.settings.skills.SkillDiscoveryService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.io.File

class SkillsTest : IntegrationTest() {

    fun testDiscoversSkillFromFilesystemAndLoadsIt() {
        clearSkillsDirectory()
        writeSkillFile(
            folder = "kotlin-test-writer",
            content = """
                ---
                name: kotlin-test-writer
                title: Kotlin Test Writer
                description: Write focused Kotlin tests.
                ---
                
                # Kotlin Test Writer
                
                Always assert behavior and edge cases.
            """.trimIndent()
        )
        val discovered = project
            .service<SkillDiscoveryService>()
            .listSkills()
        assertThat(discovered).hasSize(1)
        assertThat(discovered.first())
            .extracting("name", "title", "description")
            .contains("kotlin-test-writer", "Kotlin Test Writer", "Write focused Kotlin tests.")
        project.service<AgentService>().clearPendingMessages("skills-session")

        val result = runBlocking {
            LoadSkillTool(project, "skills-session", HookManager(project))
                .execute(LoadSkillTool.Args("kotlin-test-writer"))
        }

        assertThat(result).isInstanceOf(LoadSkillTool.Result.Success::class.java)
        val queued = project.service<AgentService>().getPendingMessages("skills-session")
        assertThat(queued).hasSize(1)
        assertThat(queued.first().text).isEqualTo(
            """
            <loaded_skill>
            name: kotlin-test-writer
            title: Kotlin Test Writer
            description: Write focused Kotlin tests.
            source: .proxyai/skills/kotlin-test-writer/SKILL.md

            # Kotlin Test Writer

            Always assert behavior and edge cases.
            </loaded_skill>
        """.trimIndent()
        )
    }

    fun testLoadSkillReturnsErrorForUnknownSkill() {
        clearSkillsDirectory()
        writeSkillFile(
            folder = "style-guide",
            content = """
                ---
                name: style-guide
                description: Formatting rules.
                ---
                
                # Style Guide
                
                Prefer concise naming.
            """.trimIndent()
        )

        val result = runBlocking {
            LoadSkillTool(project, "skills-session", HookManager(project))
                .execute(LoadSkillTool.Args("missing-skill"))
        }

        assertThat(result).isInstanceOf(LoadSkillTool.Result.Error::class.java)
        val message = (result as LoadSkillTool.Result.Error).message
        assertThat(message).isEqualTo("Skill 'missing-skill' not found. Available skills: style-guide (Style Guide)")
    }

    fun testLoadSkillRequiresApprovalAndCanBeRejected() {
        clearSkillsDirectory()
        writeSkillFile(
            folder = "docs-style",
            content = """
                ---
                name: docs-style
                description: Documentation style rules.
                ---
                
                # Docs Style
                
                Keep examples concise.
            """.trimIndent()
        )
        project.service<AgentService>().clearPendingMessages("skills-session")
        val delegate = LoadSkillTool(project, "skills-session", HookManager(project))
        val tool = ConfirmingLoadSkillTool(delegate, project) { _, _ -> false }

        val result = runBlocking {
            tool.execute(LoadSkillTool.Args("docs-style"))
        }

        assertThat(result).isInstanceOf(LoadSkillTool.Result.Error::class.java)
        val error = result as LoadSkillTool.Result.Error
        assertThat(error.message).isEqualTo("User rejected loading skill 'docs-style'")
        val queued = project.service<AgentService>().getPendingMessages("skills-session")
        assertThat(queued).isEmpty()
    }

    fun testLoadSkillApprovalUsesSkillNameAndDescription() {
        clearSkillsDirectory()
        writeSkillFile(
            folder = "humanizer",
            content = """
                ---
                name: humanizer
                description: Remove signs of AI-generated writing.
                ---
                
                # Humanizer
                
                Rewrite text to sound natural.
            """.trimIndent()
        )
        var capturedTitle: String? = null
        var capturedDetails: String? = null
        val delegate = LoadSkillTool(project, "skills-session", HookManager(project))
        val tool = ConfirmingLoadSkillTool(delegate, project) { title, details ->
            capturedTitle = title
            capturedDetails = details
            false
        }

        runBlocking {
            tool.execute(LoadSkillTool.Args("humanizer"))
        }

        assertThat(capturedTitle).isEqualTo("Load skill humanizer into context?")
        assertThat(capturedDetails).isEqualTo("Remove signs of AI-generated writing.")
    }

    fun testSkillDiscoveryParsesMultilineFrontmatterDescription() {
        clearSkillsDirectory()
        writeSkillFile(
            folder = "multiline",
            content = """
                ---
                name: multiline
                description: |
                  First line.
                  Second line.
                ---
                
                # Multi
                
                Body.
            """.trimIndent()
        )

        val skill = project.service<SkillDiscoveryService>()
            .listSkills()
            .firstOrNull { it.name == "multiline" }

        assertThat(skill).isNotNull
        assertThat(skill!!.description).isEqualTo("First line.\nSecond line.")
    }

    private fun clearSkillsDirectory() {
        val skillsDir = File(project.basePath, ".proxyai/skills")
        if (skillsDir.exists()) {
            skillsDir.deleteRecursively()
        }
    }

    private fun writeSkillFile(folder: String, content: String): File {
        val skillFile = File(project.basePath, ".proxyai/skills/$folder/SKILL.md")
        skillFile.parentFile.mkdirs()
        skillFile.writeText(content)
        return skillFile
    }
}
