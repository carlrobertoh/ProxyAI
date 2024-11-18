package ee.carlrobert.codegpt.settings.prompts

import com.intellij.openapi.components.*
import ee.carlrobert.codegpt.actions.editor.EditorActionsUtil
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.persona.PersonaSettings
import ee.carlrobert.codegpt.util.file.FileUtil.getResourceContent


@Service
@State(
    name = "CodeGPT_PromptsSettings",
    storages = [Storage("CodeGPT_PromptsSettings.xml")]
)
class PromptsSettings :
    SimplePersistentStateComponent<PromptsSettingsState>(PromptsSettingsState())

class PromptsSettingsState : BaseState() {
    var coreActions by property(CoreActionsState())
    var chatActions by property(ChatActionsState())
    var personas by property(PersonasState())
}

class CoreActionsState : BaseState() {

    companion object {
        val DEFAULT_EDIT_CODE_PROMPT = getResourceContent("/prompts/edit-code.txt")
        val DEFAULT_GENERATE_COMMIT_MESSAGE_PROMPT =
            getResourceContent("/prompts/generate-commit-message.txt")
        val DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT =
            getResourceContent("/prompts/generate-name-lookups.txt")
        val DEFAULT_FIX_COMPILE_ERRORS_PROMPT =
            getResourceContent("/prompts/fix-compile-errors.txt")
    }

    var editCode by property(CoreActionPromptDetailsState().apply {
        name = "Edit Code"
        code = "EDIT_CODE"
        instructions = DEFAULT_EDIT_CODE_PROMPT
    })
    var fixCompileErrors by property(CoreActionPromptDetailsState().apply {
        name = "Fix Compile Errors"
        code = "FIX_COMPILE_ERRORS"
        instructions = DEFAULT_FIX_COMPILE_ERRORS_PROMPT
    })
    var generateCommitMessage by property(CoreActionPromptDetailsState().apply {
        name = "Generate Commit Message"
        code = "GENERATE_COMMIT_MESSAGE"
        instructions = service<ConfigurationSettings>().state.commitMessagePrompt
    })
    var generateNameLookups by property(CoreActionPromptDetailsState().apply {
        name = "Generate Name Lookups"
        code = "GENERATE_NAME_LOOKUPS"
        instructions = DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT
    })
}

class PersonasState : BaseState() {

    companion object {
        val DEFAULT_PERSONA_PROMPT = getResourceContent("/prompts/default-completion.txt")
        val DEFAULT_PERSONA = PersonaPromptDetailsState().apply {
            id = 1L
            name = "CodeGPT Default"
            instructions = DEFAULT_PERSONA_PROMPT
        }
    }

    var selectedPersona by property(DEFAULT_PERSONA)
    var prompts by list<PersonaPromptDetailsState>()

    init {
        prompts.add(DEFAULT_PERSONA)
        prompts.add(PersonaPromptDetailsState().apply {
            id = 2L
            name = "Rubber Duck"
            instructions = getResourceContent("/prompts/rubber-duck.txt")
        })

        // migrate old personas
        var nextPersonaIndex = 3L
        prompts.addAll(service<PersonaSettings>().state.userCreatedPersonas
            .map {
                val newState = PersonaPromptDetailsState().apply {
                    id = nextPersonaIndex
                    name = it.name
                    instructions = it.instructions
                }
                nextPersonaIndex++
                newState
            })
    }
}

class ChatActionsState : BaseState() {
    var prompts by list<ChatActionPromptDetailsState>()
    var startInNewWindow by property(true)

    companion object {
        const val DEFAULT_FIND_BUGS_PROMPT =
            "Find bugs and output code with bugs fixed in the following code: {SELECTION}"
        const val DEFAULT_WRITE_TESTS_PROMPT = "Write Tests for the selected code {SELECTION}"
        const val DEFAULT_EXPLAIN_PROMPT = "Explain the selected code {SELECTION}"
        const val DEFAULT_REFACTOR_PROMPT = "Refactor the selected code {SELECTION}"
        const val DEFAULT_OPTIMIZE_PROMPT = "Optimize the selected code {SELECTION}"
    }

    init {
        prompts.add(ChatActionPromptDetailsState().apply {
            id = 1L
            code = "FIND_BUGS"
            name = "Find Bugs"
            instructions = DEFAULT_FIND_BUGS_PROMPT
        })
        prompts.add(ChatActionPromptDetailsState().apply {
            id = 2L
            code = "WRITE_TESTS"
            name = "Write Tests"
            instructions = DEFAULT_WRITE_TESTS_PROMPT
        })
        prompts.add(ChatActionPromptDetailsState().apply {
            id = 3L
            code = "EXPLAIN"
            name = "Explain"
            instructions = DEFAULT_EXPLAIN_PROMPT
        })
        prompts.add(ChatActionPromptDetailsState().apply {
            id = 4L
            code = "REFACTOR"
            name = "Refactor"
            instructions = DEFAULT_REFACTOR_PROMPT
        })
        prompts.add(ChatActionPromptDetailsState().apply {
            id = 5L
            code = "OPTIMIZE"
            name = "Optimize"
            instructions = DEFAULT_OPTIMIZE_PROMPT
        })

        // migrate old chat actions
        var nextChatActionIndex = 6L
        prompts.addAll(service<ConfigurationSettings>().state.tableData
            .filterNot { entry ->
                EditorActionsUtil.DEFAULT_ACTIONS.any { it.key == entry.key && it.value == entry.value }
            }
            .map {
                val newState = ChatActionPromptDetailsState().apply {
                    id = nextChatActionIndex
                    name = it.key
                    instructions = it.value
                }
                nextChatActionIndex++
                newState
            })
    }
}

abstract class PromptDetailsState : BaseState() {
    var name by string()
    var instructions by string()
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
}
