package ee.carlrobert.codegpt.settings.prompts

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.components.*
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.actions.editor.EditorActionsUtil
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.persona.PersonaDetailsState
import ee.carlrobert.codegpt.settings.persona.PersonaSettings
import ee.carlrobert.codegpt.util.file.FileUtil.getResourceContent

@Service
@State(
    name = "CodeGPT_PromptsSettings",
    storages = [Storage("CodeGPT_PromptsSettings.xml")]
)
class PromptsSettings :
    SimplePersistentStateComponent<PromptsSettingsState>(PromptsSettingsState()) {

    companion object {
        @JvmStatic
        fun getSelectedPersonaSystemPrompt(): String {
            val selectedPersona = service<PromptsSettings>().state.personas.selectedPersona
            return (selectedPersona.instructions ?: "")
                .addProjectPath()
        }
    }

    override fun initializeComponent() {
        super.initializeComponent()
    }
}

class PromptsSettingsState : BaseState() {
    var coreActions by property(CoreActionsState())
    var chatActions by property(ChatActionsState())
    var personas by property(PersonasState())
}

class CoreActionsState : BaseState() {

    companion object {
        private const val PROMPTS_BASE_PATH = "/prompts/core/"

        val DEFAULT_AUTO_APPLY_PROMPT = loadPrompt("auto-apply.txt")
        val DEFAULT_INLINE_EDIT_PROMPT = loadPrompt("inline-edit.txt")
        val DEFAULT_GENERATE_COMMIT_MESSAGE_PROMPT = loadPrompt("generate-commit-message.txt")
        val DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT = loadPrompt("generate-name-lookups.txt")
        val DEFAULT_FIX_COMPILE_ERRORS_PROMPT = loadPrompt("fix-compile-errors.txt")
        val DEFAULT_REVIEW_CHANGES_PROMPT = loadPrompt("review-changes.txt")

        private fun loadPrompt(fileName: String) =
            getResourceContent(PROMPTS_BASE_PATH + fileName)
    }

    var autoApply by property(
        createCoreAction(
            "Auto Apply", "AUTO_APPLY", DEFAULT_AUTO_APPLY_PROMPT
        )
    )

    var inlineEdit by property(
        createCoreAction(
            "Inline Edit", "INLINE_EDIT", DEFAULT_INLINE_EDIT_PROMPT
        )
    )

    var fixCompileErrors by property(
        createCoreAction(
            "Fix Compile Errors", "FIX_COMPILE_ERRORS", DEFAULT_FIX_COMPILE_ERRORS_PROMPT
        )
    )

    var generateCommitMessage by property(
        createCoreAction(
            "Generate Commit Message",
            "GENERATE_COMMIT_MESSAGE",
            service<ConfigurationSettings>().state.commitMessagePrompt
        )
    )

    var generateNameLookups by property(
        createCoreAction(
            "Generate Name Lookups", "GENERATE_NAME_LOOKUPS", DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT
        )
    )

    var reviewChanges by property(
        createCoreAction(
            "Review Changes", "REVIEW_CHANGES", DEFAULT_REVIEW_CHANGES_PROMPT
        )
    )

    private fun createCoreAction(name: String, code: String, instructions: String?) =
        CoreActionPromptDetailsState().apply {
            this.name = name
            this.code = code
            this.instructions = instructions
        }
}

class PersonasState : BaseState() {

    companion object {
        const val DEFAULT_PERSONA_ID = 1L
        const val DEFAULT_PERSONA_NAME = "Default Persona"
        private const val DEFAULT_PERSONA_PROMPT_PATH = "/prompts/persona/default-persona.txt"

        val DEFAULT_PERSONA_PROMPT = getResourceContent(DEFAULT_PERSONA_PROMPT_PATH)
        val DEFAULT_PERSONA = createDefaultPersona()

        private fun createDefaultPersona() = PersonaPromptDetailsState().apply {
            id = DEFAULT_PERSONA_ID
            name = DEFAULT_PERSONA_NAME
            instructions = DEFAULT_PERSONA_PROMPT
        }
    }

    var selectedPersona by property(DEFAULT_PERSONA)
    var prompts by list<PersonaPromptDetailsState>()

    init {
        addDefaultPersonas()
        migrateOldPersonas()
    }

    private fun addDefaultPersonas() {
        prompts.addAll(
            listOf(
                DEFAULT_PERSONA,
                createRubberDuckPersona()
            )
        )
    }

    private fun createRubberDuckPersona() = PersonaPromptDetailsState().apply {
        id = 2L
        name = "Rubber Duck"
        instructions = getResourceContent("/prompts/persona/rubber-duck.txt")
    }

    private fun migrateOldPersonas() {
        var nextId = 3L
        val migratedPersonas = service<PersonaSettings>().state.userCreatedPersonas
            .map { oldPersona -> createMigratedPersona(nextId++, oldPersona) }

        prompts.addAll(migratedPersonas)
    }

    private fun createMigratedPersona(id: Long, oldPersona: PersonaDetailsState) =
        PersonaPromptDetailsState().apply {
            this.id = id
            this.name = oldPersona.name
            this.instructions = oldPersona.instructions
        }
}

class ChatActionsState : BaseState() {
    var prompts by list<ChatActionPromptDetailsState>()
    var startInNewWindow by property(false)

    companion object {
        private const val PROMPTS_BASE_PATH = "/prompts/chat/"

        val DEFAULT_FIND_BUGS_PROMPT = loadPrompt("find-bugs.txt")
        val DEFAULT_WRITE_TESTS_PROMPT = loadPrompt("write-tests.txt")
        val DEFAULT_EXPLAIN_PROMPT = loadPrompt("explain.txt")
        val DEFAULT_REFACTOR_PROMPT = loadPrompt("refactor.txt")
        val DEFAULT_OPTIMIZE_PROMPT = loadPrompt("optimize.txt")

        private fun loadPrompt(fileName: String) =
            getResourceContent(PROMPTS_BASE_PATH + fileName)
    }

    init {
        addDefaultChatActions()
        migrateOldChatActions()
    }

    private fun addDefaultChatActions() {
        prompts.addAll(
            listOf(
                createChatAction(1L, "FIND_BUGS", "Find Bugs", DEFAULT_FIND_BUGS_PROMPT),
                createChatAction(2L, "WRITE_TESTS", "Write Tests", DEFAULT_WRITE_TESTS_PROMPT),
                createChatAction(3L, "EXPLAIN", "Explain", DEFAULT_EXPLAIN_PROMPT),
                createChatAction(4L, "REFACTOR", "Refactor", DEFAULT_REFACTOR_PROMPT),
                createChatAction(5L, "OPTIMIZE", "Optimize", DEFAULT_OPTIMIZE_PROMPT)
            )
        )
    }

    private fun createChatAction(id: Long, code: String, name: String, instructions: String) =
        ChatActionPromptDetailsState().apply {
            this.id = id
            this.code = code
            this.name = name
            this.instructions = instructions
        }


    private fun migrateOldChatActions() {
        var nextId = 6L
        val migratedActions = service<ConfigurationSettings>().state.tableData
            .filterNot { entry -> isDefaultAction(entry) }
            .map { entry -> createMigratedAction(nextId++, entry) }

        prompts.addAll(migratedActions)
    }

    private fun isDefaultAction(entry: Map.Entry<String, String>) =
        EditorActionsUtil.DEFAULT_ACTIONS.any {
            it.key == entry.key && it.value == entry.value
        }

    private fun createMigratedAction(id: Long, entry: Map.Entry<String, String>) =
        ChatActionPromptDetailsState().apply {
            this.id = id
            this.name = entry.key
            this.instructions = entry.value
        }
}

abstract class PromptDetailsState : BaseState() {
    var name by string()
    var instructions by string()
}

class CodeAssistantPromptDetailsState : PromptDetailsState() {
    var code by string()
}

class CoreActionPromptDetailsState : PromptDetailsState() {
    var code by string()
}

class ChatActionPromptDetailsState : PromptDetailsState() {
    var id by property(1L)
    var code by string()
}

class PersonaPromptDetailsState : PromptDetailsState() {
    var id by property(1L)
    var disabled by property(false)
}

@JvmRecord
data class PersonaDetails(val id: Long, val name: String, val instructions: String)

fun String.addProjectPath(virtualFile: VirtualFile? = null): String {
    val projectPath = virtualFile?.let { file ->
        ProjectManager.getInstance().openProjects
            .firstOrNull { project ->
                project.projectFile?.parent?.path?.let { projectDir ->
                    file.path.startsWith(projectDir)
                } == true
            }?.guessProjectDir()?.path
    } ?: ProjectUtil.getActiveProject()?.guessProjectDir()?.path ?: "UNDEFINED"

    return replace("{{project_path}}", projectPath)
}