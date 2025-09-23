package ee.carlrobert.codegpt.settings.prompts

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.settings.configuration.ChatMode
import testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat

class FilteredPromptsServiceTest : IntegrationTest() {

    private lateinit var filteredPromptsService: FilteredPromptsService
    private lateinit var promptsSettings: PromptsSettings

    override fun setUp() {
        super.setUp()
        filteredPromptsService = project.service()
        promptsSettings = service()
        
        promptsSettings.state.coreActions.autoApply.instructions = null
        promptsSettings.state.coreActions.inlineEdit.instructions = null
        promptsSettings.state.personas.selectedPersona = PersonaPromptDetailsState().apply {
            id = 999L
            name = "Test Persona"
            instructions = null
        }
    }

    fun testAutoApplyPromptReturnsSearchReplaceFormatInEditMode() {
        val prompt = filteredPromptsService.getFilteredAutoApplyPrompt(ChatMode.EDIT)

        assertThat(prompt).contains("<<<<<<< SEARCH", ">>>>>>> REPLACE")
    }

    fun testAutoApplyPromptReturnsSearchReplaceFormatInAskMode() {
        val prompt = filteredPromptsService.getFilteredAutoApplyPrompt(ChatMode.ASK)

        assertThat(prompt).contains("<<<<<<< SEARCH", ">>>>>>> REPLACE")
    }

    fun testEditCodePromptReturnsDefaultFormatInEditMode() {
        val prompt = filteredPromptsService.getFilteredEditCodePrompt(ChatMode.EDIT)

        assertThat(prompt).isEqualTo(CoreActionsState.DEFAULT_INLINE_EDIT_PROMPT)
    }

    fun testEditCodePromptReturnsSimpleFormatInAskMode() {
        val prompt = filteredPromptsService.getFilteredEditCodePrompt(ChatMode.ASK)

        assertThat(prompt).doesNotContain("<<<<<<< SEARCH", ">>>>>>> REPLACE")
        assertThat(prompt).containsAnyOf("complete modified code", "complete updated code")
    }

    fun testPersonaPromptFilteringInAskMode() {
        val personaWithSearchReplace = """
            You are a helpful assistant.
            For refactoring or editing an existing file, always generate a SEARCH/REPLACE block.
            When generating SEARCH/REPLACE blocks:
            - Include surrounding context
            - Keep SEARCH blocks concise while including necessary surrounding lines.
            
            Example:
            ```java:/path/to/Calculator.java
            <<<<<<< SEARCH
            public int add(int a, int b) {
                return a + b;
            }
            =======
            public int add(int a, int b) {
                // Add validation
                if (a < 0 || b < 0) {
                    throw new IllegalArgumentException("Negative numbers not allowed");
                }
                return a + b;
            }
            >>>>>>> REPLACE
            ```
        """.trimIndent()
        promptsSettings.state.personas.selectedPersona.instructions = personaWithSearchReplace

        val filteredPrompt = filteredPromptsService.getFilteredPersonaPrompt(ChatMode.ASK)

        assertThat(filteredPrompt).doesNotContain("always generate a SEARCH/REPLACE block")
        assertThat(filteredPrompt).doesNotContain("<<<<<<< SEARCH")
        assertThat(filteredPrompt).contains("provide the complete modified code")
        assertThat(filteredPrompt).contains("You are a helpful assistant")
    }

    fun testPersonaPromptUnchangedInEditMode() {
        val originalPersonaPrompt = """
            You are a code assistant.
            For refactoring or editing an existing file, always generate a SEARCH/REPLACE block.
        """.trimIndent()
        promptsSettings.state.personas.selectedPersona.instructions = originalPersonaPrompt

        val filteredPrompt = filteredPromptsService.getFilteredPersonaPrompt(ChatMode.EDIT)

        assertThat(filteredPrompt).isEqualTo(FilteredPromptsService.DEFAULT_PERSONA_EDIT_MODE_PROMPT)
    }

    fun testCustomAutoApplyPromptInEditMode() {
        val customPrompt = "Custom auto apply prompt with special instructions"
        promptsSettings.state.coreActions.autoApply.instructions = customPrompt

        val prompt = filteredPromptsService.getFilteredAutoApplyPrompt(ChatMode.EDIT)

        assertThat(prompt).isEqualTo(customPrompt)
    }

    fun testCustomAutoApplyPromptUsedInAskMode() {
        val customPrompt = "Custom prompt with <<<<<<< SEARCH instructions"
        promptsSettings.state.coreActions.autoApply.instructions = customPrompt

        val prompt = filteredPromptsService.getFilteredAutoApplyPrompt(ChatMode.ASK)

        assertThat(prompt).contains(customPrompt)
    }

    fun testEmptyPersonaPromptHandling() {
        promptsSettings.state.personas.selectedPersona.instructions = ""

        val prompt = filteredPromptsService.getFilteredPersonaPrompt(ChatMode.ASK)

        assertThat(prompt).isEmpty()
    }

    fun testNullPersonaPromptHandling() {
        promptsSettings.state.personas.selectedPersona.instructions = null

        val prompt = filteredPromptsService.getFilteredPersonaPrompt(ChatMode.ASK)

        assertThat(prompt).isEmpty()
    }

    fun testPromptCachingForPerformance() {
        val complexPersonaPrompt = """
            Complex prompt with lots of content.
            For refactoring or editing an existing file, always generate a SEARCH/REPLACE block.
            When generating SEARCH/REPLACE blocks: do this and that.
            For editing existing files, use this SEARCH/REPLACE structure: example here.
            Example:
            ```java:/path/to/Calculator.java
            <<<<<<< SEARCH
            old code
            =======
            new code
            >>>>>>> REPLACE
            ```
        """.trimIndent()
        promptsSettings.state.personas.selectedPersona.instructions = complexPersonaPrompt
        val startTime = System.currentTimeMillis()
        val iterations = 1000
        val maxDurationMs = 50

        repeat(iterations) {
            filteredPromptsService.getFilteredPersonaPrompt(ChatMode.ASK)
        }

        val duration = System.currentTimeMillis() - startTime
        assertThat(duration).isLessThan(maxDurationMs.toLong())
    }

    fun testModeSwitchingBehavior() {
        val personaPrompt = """
            You are a helpful assistant.
            For refactoring or editing an existing file, always generate a SEARCH/REPLACE block.
            When generating SEARCH/REPLACE blocks: follow these guidelines.
        """.trimIndent()
        promptsSettings.state.personas.selectedPersona.instructions = personaPrompt

        val askModePrompt = filteredPromptsService.getFilteredPersonaPrompt(ChatMode.ASK)
        val editModePrompt = filteredPromptsService.getFilteredPersonaPrompt(ChatMode.EDIT)

        assertThat(askModePrompt).isNotEqualTo(editModePrompt)
        assertThat(askModePrompt).contains("provide the complete modified code")
        assertThat(askModePrompt).doesNotContain("always generate a SEARCH/REPLACE block")
        assertThat(editModePrompt).isEqualTo(FilteredPromptsService.DEFAULT_PERSONA_EDIT_MODE_PROMPT)
    }
}