package ee.carlrobert.codegpt.settings.prompts

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.settings.configuration.ChatMode
import ee.carlrobert.codegpt.util.file.FileUtil.getResourceContent

/**
 * Service responsible for filtering prompts based on the chat mode setting.
 *
 * Chat modes:
 * - ASK mode: Returns prompts with instructions to provide complete code
 * - EDIT mode: Returns prompts with SEARCH/REPLACE instructions
 * - AGENT mode: Currently not implemented
 */
@Service
class FilteredPromptsService {

    fun getFilteredAutoApplyPrompt(chatMode: ChatMode): String =
        getOriginalAutoApplyPrompt().addProjectPath()

    fun getFilteredAutoApplyPrompt(chatMode: ChatMode, virtualFile: VirtualFile?): String =
        getOriginalAutoApplyPrompt().addProjectPath(virtualFile)

    fun getFilteredPersonaPrompt(chatMode: ChatMode): String {
        val selectedPersona = service<PromptsSettings>().state.personas.selectedPersona
        
        return when (chatMode) {
            ChatMode.EDIT -> DEFAULT_PERSONA_EDIT_MODE_PROMPT
            ChatMode.ASK -> {
                if (isDefaultPersona(selectedPersona)) {
                    PersonasState.DEFAULT_PERSONA_PROMPT
                } else {
                    val originalPrompt = getOriginalPersonaPrompt()
                    filterOutSearchReplaceInstructions(originalPrompt)
                }
            }
            ChatMode.AGENT -> PersonasState.DEFAULT_PERSONA_PROMPT
        }
    }

    fun getFilteredEditCodePrompt(chatMode: ChatMode): String =
        when (chatMode) {
            ChatMode.EDIT -> getOriginalEditCodePrompt()
            ChatMode.ASK -> getSimpleEditCodePrompt()
            ChatMode.AGENT -> getSimpleEditCodePrompt()
        }

    private fun isDefaultPersona(persona: PersonaPromptDetailsState) =
        persona.id == DEFAULT_PERSONA_ID

    private fun getOriginalAutoApplyPrompt() =
        service<PromptsSettings>().state.coreActions.autoApply.instructions
            ?: CoreActionsState.DEFAULT_AUTO_APPLY_PROMPT

    private fun getOriginalEditCodePrompt() =
        service<PromptsSettings>().state.coreActions.inlineEdit.instructions
            ?: CoreActionsState.DEFAULT_INLINE_EDIT_PROMPT

    private fun getOriginalPersonaPrompt(): String {
        val selectedPersona = service<PromptsSettings>().state.personas.selectedPersona
        
        return selectedPersona.instructions ?: when {
            isDefaultPersona(selectedPersona) -> PersonasState.DEFAULT_PERSONA_PROMPT
            else -> ""
        }
    }


    private fun getSimpleEditCodePrompt(): String =
        getResourceContent("/prompts/core/edit-code-ask-mode.txt")

    private fun filterOutSearchReplaceInstructions(prompt: String): String {
        if (prompt.isEmpty()) return ""

        return performPromptReplacements(prompt)
    }

    private fun performPromptReplacements(prompt: String): String {
        var result = prompt.replace(
            SEARCH_REPLACE_INSTRUCTION,
            COMPLETE_CODE_INSTRUCTION
        )

        REGEX_REPLACEMENTS.forEach { (regex, replacement) ->
            result = regex.replace(result, replacement)
        }

        return result
    }

    companion object {
        private const val DEFAULT_PERSONA_ID = 1L
        private const val EDIT_MODE_PROMPT_RESOURCE =
            "/prompts/persona/default-persona-edit-mode.txt"
        private const val SEARCH_REPLACE_INSTRUCTION =
            "For refactoring or editing an existing file, always generate a SEARCH/REPLACE block."
        private const val COMPLETE_CODE_INSTRUCTION =
            "For refactoring or editing an existing file, provide the complete modified code."

        val DEFAULT_PERSONA_EDIT_MODE_PROMPT = getResourceContent(EDIT_MODE_PROMPT_RESOURCE)

        private val SEARCH_REPLACE_BLOCKS_REGEX = Regex(
            "When generating SEARCH/REPLACE blocks:.*?Keep SEARCH blocks concise while including necessary surrounding lines\\.",
            RegexOption.DOT_MATCHES_ALL
        )

        private const val CODE_MODIFICATIONS_TEXT = """When providing code modifications:
   a. Ensure each code block represents a complete, working solution.
   b. Include all necessary context and dependencies.
   c. Maintain proper code formatting and structure."""

        private val SEARCH_REPLACE_STRUCTURE_REGEX = Regex(
            "For editing existing files, use this SEARCH/REPLACE structure:.*?>>>>>>> REPLACE\n   ```",
            RegexOption.DOT_MATCHES_ALL
        )

        private const val COMPLETE_CODE_STRUCTURE =
            """For editing existing files, provide the complete modified code:
   ```[language]:[full_file_path]
   [complete modified file content]
   ```"""

        private val CALCULATOR_EXAMPLE_REGEX = Regex(
            "Example:\\s*```[^`]*<<<<<<< SEARCH.*?>>>>>>> REPLACE[^`]*```",
            RegexOption.DOT_MATCHES_ALL
        )

        private const val CALCULATOR_EXAMPLE_REPLACEMENT = """Example:
   ```java:/path/to/Calculator.java
   public int add(int a, int b) {
       // Added input validation
       if (a < 0 || b < 0) {
           throw new IllegalArgumentException("Negative numbers not allowed");
       }
       return a + b;
   }
   ```"""

        private val REGEX_REPLACEMENTS = listOf(
            SEARCH_REPLACE_BLOCKS_REGEX to CODE_MODIFICATIONS_TEXT,
            SEARCH_REPLACE_STRUCTURE_REGEX to COMPLETE_CODE_STRUCTURE,
            CALCULATOR_EXAMPLE_REGEX to CALCULATOR_EXAMPLE_REPLACEMENT
        )
    }
}