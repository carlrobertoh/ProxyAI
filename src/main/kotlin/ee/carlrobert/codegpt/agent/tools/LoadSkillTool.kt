package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.AgentService
import ee.carlrobert.codegpt.agent.MessageWithContext
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.settings.skills.SkillDescriptor
import ee.carlrobert.codegpt.settings.skills.SkillDiscoveryService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class LoadSkillTool(
    private val project: Project,
    private val sessionId: String,
    hookManager: HookManager,
) : BaseTool<LoadSkillTool.Args, LoadSkillTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "LoadSkill",
    description = """
        Load a skill within the main conversation
        
        When users ask you to perform tasks, check if any of the available skills match. Skills provide specialized capabilities and domain knowledge.
        
        When users reference a \"slash command\" or \"/<something>\" (e.g., \"/commit\", \"/review-pr\"), they are referring to a skill. Use this tool to invoke it.
        
        How to invoke:
        - Use this tool with the skill name and optional arguments
        - Examples:
          - `skill: \"pdf\"` - invoke the pdf skill
          - `skill: \"commit\", args: \"-m 'Fix bug'\"` - invoke with arguments
          - `skill: \"review-pr\", args: \"123\"` - invoke with arguments
          - `skill: \"ms-office-suite:pdf\"` - invoke using fully qualified name
          
        Important:
        - Available skills are listed in system-reminder messages in the conversation
        - When a skill matches the user's request, this is a BLOCKING REQUIREMENT: invoke the relevant Skill tool BEFORE generating any other response about the task
        - NEVER mention a skill without actually calling this tool
        - Do not invoke a skill that is already running
        - Do not use this tool for built-in CLI commands (like /help, /clear, etc.)
        - If you see a <command-name> tag in the current conversation turn, the skill has ALREADY been loaded - follow the instructions directly instead of calling this tool again.
        """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
    sessionId = sessionId
) {

    @Serializable
    data class Args(
        @property:LLMDescription("Exact title of the skill to load.")
        @SerialName("skill_name")
        val skillName: String
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val name: String,
            val title: String,
            val description: String,
            val sourcePath: String,
            val loadedContent: String,
            val queued: Boolean = true
        ) : Result()

        @Serializable
        data class Error(
            val message: String
        ) : Result()
    }

    override suspend fun doExecute(args: Args): Result {
        val skills = project.service<SkillDiscoveryService>().listSkills()
        val requested = args.skillName.trim()
        val skill = skills.firstOrNull {
            it.name.equals(requested, ignoreCase = true) || it.title.equals(requested, ignoreCase = true)
        }
        if (skill == null) {
            val available = skills.map { "${it.name} (${it.title})" }.sorted()
            return Result.Error(
                if (available.isEmpty()) {
                    "No skills found in .proxyai/skills."
                } else {
                    "Skill '$requested' not found. Available skills: ${available.joinToString(", ")}"
                }
            )
        }

        val message = buildLoadedSkillMessage(skill)
        project.service<AgentService>().addToQueue(
            MessageWithContext(
                text = message,
                tags = emptyList(),
                uiVisible = false,
                uiText = "Skill '${skill.name}' loaded into context"
            ),
            sessionId
        )

        return Result.Success(
            name = skill.name,
            title = skill.title,
            description = skill.description,
            sourcePath = skill.relativePath,
            loadedContent = skill.content
        )
    }

    override fun createDeniedResult(
        originalArgs: Args,
        deniedReason: String
    ): Result {
        return Result.Error(deniedReason)
    }

    override fun encodeResultToString(result: Result): String {
        return when (result) {
            is Result.Success -> "Loaded skill '${result.name}' from ${result.sourcePath}. Its content was queued as a user message."
            is Result.Error -> "LoadSkill failed: ${result.message}"
        }
    }

    companion object {
        internal fun buildLoadedSkillMessage(skill: SkillDescriptor): String {
            return buildString {
                appendLine("<loaded_skill>")
                appendLine("name: ${skill.name}")
                appendLine("title: ${skill.title}")
                appendLine("description: ${skill.description}")
                appendLine("source: ${skill.relativePath}")
                appendLine()
                appendLine(skill.content.trim())
                appendLine("</loaded_skill>")
            }.trim()
        }
    }
}
