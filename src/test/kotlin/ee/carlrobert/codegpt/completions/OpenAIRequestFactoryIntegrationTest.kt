package ee.carlrobert.codegpt.completions

import ai.koog.prompt.message.Message as KoogMessage
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.completions.factory.OpenAIRequestFactory
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.settings.configuration.ChatMode
import ee.carlrobert.codegpt.settings.prompts.*
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.util.file.FileUtil.getResourceContent

import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.io.File

class OpenAIRequestFactoryIntegrationTest : IntegrationTest() {

    fun testDefaultPersonaUsesEditModePromptWhenEnabled() {
        useOpenAIService("gpt-4o")
        service<PromptsSettings>().state.personas.selectedPersona = PersonasState.DEFAULT_PERSONA
        val conversation = ConversationService.getInstance().startConversation(project)
        val message = Message("Please refactor this code")
        val callParameters = ChatCompletionParameters
            .builder(conversation, message)
            .chatMode(ChatMode.EDIT)
            .build()

        val prompt = OpenAIRequestFactory().createChatCompletionPrompt(callParameters)

        val systemMessages = prompt.messages
            .filterIsInstance<KoogMessage.System>()
            .map { it.content }
        assertThat(systemMessages).isNotEmpty()
        val systemContent = systemMessages.first()
        assertThat(systemContent)
            .contains("You are an AI programming assistant integrated into a JetBrains IDE plugin.")
            .contains("<project_path>")
            .contains("</project_path>")
            .contains("SEARCH/REPLACE")
            .contains("JetBrains Navigation Links (MANDATORY)")
    }

    fun testToolCallRequestBuildsAssistantToolCallsMessage() {
        useOpenAIService("gpt-4o")
        service<PromptsSettings>().state.personas.selectedPersona = PersonasState.DEFAULT_PERSONA
        val conversation = ConversationService.getInstance().startConversation(project)
        val message = Message("Run tool").apply {
            response = "Calling tool..."
            toolCalls =
                listOf(ChatToolCall(null, "tc_1", "function", ChatToolFunction("search", "{}")))
        }
        val callParameters = ChatCompletionParameters.builder(conversation, message)
            .requestType(RequestType.TOOL_CALL_REQUEST).build()
        val filtered =
            service<FilteredPromptsService>().getFilteredPersonaPrompt(ChatMode.ASK)
                .addProjectPath()
        val expected = listOf(
            mapOf("role" to "system"),
            mapOf("role" to "user", "content" to "Run tool"),
            mapOf(
                "role" to "assistant",
                "content" to "Calling tool..."
            ),
            mapOf("role" to "tool_call", "id" to "tc_1", "tool" to "search", "args" to "{}")
        )

        val prompt = OpenAIRequestFactory().createChatCompletionPrompt(callParameters)

        val actual = normalize(prompt.messages)
        assertThat(actual.drop(1)).isEqualTo(expected.drop(1))
        assertThat(actual.first()["role"]).isEqualTo("system")
        assertThat(actual.first()["content"].toString()).contains("JetBrains Navigation Links (MANDATORY)")
    }

    fun testToolCallContinuationIncludesResultsAndHistory() {
        useOpenAIService("gpt-4o")
        service<PromptsSettings>().state.personas.selectedPersona = PersonasState.DEFAULT_PERSONA
        val conversation = ConversationService.getInstance().startConversation(project)
        val prev = Message("Prev run").apply {
            response = "Prev call"
            toolCalls = listOf(
                ChatToolCall(
                    null,
                    "tc_prev",
                    "function",
                    ChatToolFunction("list", "{\"q\":1}")
                )
            )
            addToolCallResult("tc_prev", "prev_result")
        }
        conversation.addMessage(prev)
        val current = Message("Run again").apply {
            response = "Curr call"
            toolCalls = listOf(
                ChatToolCall(
                    null,
                    "tc_curr",
                    "function",
                    ChatToolFunction("list", "{\"q\":2}")
                )
            )
            addToolCallResult("tc_curr", "curr_result")
        }
        val callParameters = ChatCompletionParameters.builder(conversation, current)
            .requestType(RequestType.TOOL_CALL_CONTINUATION).build()
        val expected = listOf(
            mapOf("role" to "system"),
            mapOf("role" to "user", "content" to "Prev run"),
            mapOf(
                "role" to "assistant",
                "content" to "Prev call"
            ),
            mapOf("role" to "tool_call", "id" to "tc_prev", "tool" to "list", "args" to "{\"q\":1}"),
            mapOf("role" to "tool_result", "id" to "tc_prev", "tool" to "list", "content" to "prev_result"),
            mapOf("role" to "user", "content" to "Run again"),
            mapOf(
                "role" to "assistant",
                "content" to "Curr call"
            ),
            mapOf("role" to "tool_call", "id" to "tc_curr", "tool" to "list", "args" to "{\"q\":2}"),
            mapOf("role" to "tool_result", "id" to "tc_curr", "tool" to "list", "content" to "curr_result"),
            mapOf("role" to "assistant", "content" to "Curr call")
        )

        val prompt = OpenAIRequestFactory().createChatCompletionPrompt(callParameters)

        val actual = normalize(prompt.messages)
        assertThat(actual.drop(1)).isEqualTo(expected.drop(1))
        assertThat(actual.first()["role"]).isEqualTo("system")
        assertThat(actual.first()["content"].toString()).contains("JetBrains Navigation Links (MANDATORY)")
    }

    fun testChatRequestEmbedsReferencedFilesInSystemMessage() {
        useOpenAIService("gpt-4o")
        service<PromptsSettings>().state.personas.selectedPersona = PersonasState.DEFAULT_PERSONA
        val conversation = ConversationService.getInstance().startConversation(project)
        val message = Message("What does the code do?")
        val refs = listOf(
            ReferencedFile("A.java", "/path/A.java", "class A {}"),
            ReferencedFile("B.kt", "/path/B.kt", "class B")
        )
        val callParameters =
            ChatCompletionParameters.builder(conversation, message).referencedFiles(refs).build()
        val filtered =
            service<FilteredPromptsService>().getFilteredPersonaPrompt(ChatMode.ASK)
                .addProjectPath()
        val expectedSystem = service<FilteredPromptsService>().applyClickableLinks(filtered)

        val prompt = OpenAIRequestFactory().createChatCompletionPrompt(callParameters)

        val msgs = prompt.messages
        assertThat(msgs).hasSize(2)
        assertThat((msgs[0] as KoogMessage.System).content).contains(expectedSystem.trim())
        assertThat((msgs[1] as KoogMessage.User).content).contains("What does the code do?")
        assertThat((msgs[1] as KoogMessage.User).content).contains("/path/A.java")
        assertThat((msgs[1] as KoogMessage.User).content).contains("class A {}")
        assertThat((msgs[1] as KoogMessage.User).content).contains("/path/B.kt")
        assertThat((msgs[1] as KoogMessage.User).content).contains("class B")
    }

    fun testDefaultPersonaIsFilteredInAskMode() {
        useOpenAIService("gpt-4o")
        service<PromptsSettings>().state.personas.selectedPersona = PersonasState.DEFAULT_PERSONA
        val conversation = ConversationService.getInstance().startConversation(project)
        val message = Message("Please refactor this code")
        val callParameters =
            ChatCompletionParameters.builder(conversation, message).chatMode(ChatMode.ASK).build()
        val filtered =
            service<FilteredPromptsService>().getFilteredPersonaPrompt(ChatMode.ASK)
                .addProjectPath()
        val expectedSystem = service<FilteredPromptsService>().applyClickableLinks(filtered)

        val prompt = OpenAIRequestFactory().createChatCompletionPrompt(callParameters)

        val systemMessages = prompt.messages.filterIsInstance<KoogMessage.System>().map { it.content }
        assertThat(systemMessages.first()).contains(expectedSystem.trim())
    }

    fun testChatRequestUsesFilteredPersonaPromptInAskMode() {
        useOpenAIService("gpt-4o")
        val personaPromptWithSearchReplace = """
            You are a helpful assistant.
            For refactoring or editing an existing file, always generate a SEARCH/REPLACE block.
            When generating SEARCH/REPLACE blocks:
            - Include surrounding context
            - Keep SEARCH blocks concise while including necessary surrounding lines.
            Example:
            ```java
            <<<<<<< SEARCH
            old code
            =======
            new code
            >>>>>>> REPLACE
            ```
        """.trimIndent()
        val customPersona = PersonaPromptDetailsState().apply {
            id = 999L  // Non-default ID
            name = "Custom Test Persona"
            instructions = personaPromptWithSearchReplace
        }
        service<PromptsSettings>().state.personas.selectedPersona = customPersona
        val conversation = ConversationService.getInstance().startConversation(project)
        val message = Message("Please refactor this code")
        val callParameters = ChatCompletionParameters
            .builder(conversation, message)
            .chatMode(ChatMode.ASK)
            .build()

        val prompt = OpenAIRequestFactory().createChatCompletionPrompt(callParameters)

        val systemMessages = prompt.messages
            .filterIsInstance<KoogMessage.System>()
            .map { it.content }
        assertThat(systemMessages).isNotEmpty()
        val systemContent = systemMessages.first()
        assertThat(systemContent)
            .startsWith("You are a helpful assistant.")
            .contains("provide the complete modified code")
            .contains("JetBrains Navigation Links (MANDATORY)")
    }

    fun testChatRequestKeepsOriginalPersonaPromptInEditMode() {
        useOpenAIService("gpt-4o")
        val personaPromptWithSearchReplace = """
            You are a helpful assistant.
            For refactoring or editing an existing file, always generate a SEARCH/REPLACE block.
        """.trimIndent()
        service<PromptsSettings>().state.personas.selectedPersona = PersonaPromptDetailsState().apply {
            id = 999L
            name = "Custom Test Persona"
            instructions = personaPromptWithSearchReplace
        }
        val conversation = ConversationService.getInstance().startConversation(project)
        val message = Message("Please refactor this code")
        val callParameters = ChatCompletionParameters
            .builder(conversation, message)
            .chatMode(ChatMode.EDIT)
            .build()

        val prompt = OpenAIRequestFactory().createChatCompletionPrompt(callParameters)

        val systemMessages = prompt.messages
            .filterIsInstance<KoogMessage.System>()
            .map { it.content }
        assertThat(systemMessages).isNotEmpty()
        val systemContent = systemMessages.first()
        assertThat(systemContent)
            .contains("You are an AI programming assistant integrated into a JetBrains IDE plugin.")
            .contains("<project_path>")
            .contains("</project_path>")
            .contains("SEARCH/REPLACE")
            .contains("JetBrains Navigation Links (MANDATORY)")
    }

    fun testInlineEditSingleRequestNoHistory() {
        useOpenAIService("gpt-4.1", FeatureType.INLINE_EDIT)
        val testFileContent = getResourceContent("/inline/TestClass.java")
        val tempFile = File.createTempFile("TestClass", ".java")
        tempFile.writeText(testFileContent)
        tempFile.deleteOnExit()
        val parameters = InlineEditCompletionParameters(
            selectedText = "myTestMethod()",
            filePath = tempFile.absolutePath,
            fileExtension = "java",
            projectBasePath = project.basePath,
            referencedFiles = null,
            gitDiff = null,
            conversation = null,
            conversationHistory = null,
            diagnosticsInfo = null
        )

        val prompt = OpenAIRequestFactory().createInlineEditPrompt(parameters)

        val systemMessage = prompt.messages[0] as KoogMessage.System
        val expectedSystem = buildExpectedInlineEditSystemPrompt(
            language = "java",
            filePath = tempFile.absolutePath,
            fileContent = testFileContent,
            projectBasePath = project.basePath,
            referencedFiles = null,
            gitDiff = null,
            conversationHistory = null,
            diagnosticsInfo = null,
        )
        assertThat(systemMessage.content).isEqualTo(expectedSystem)
        val userMessage = prompt.messages[1] as KoogMessage.User
        assertThat(userMessage.content).isEqualTo("Implement.")
    }

    fun testInlineEditFollowUpWithHistory() {
        useOpenAIService("gpt-4.1", FeatureType.INLINE_EDIT)
        val testFileContent = getResourceContent("/inline/TestClass.java")
        val tempFile = File.createTempFile("TestClass", ".java")
        tempFile.writeText(testFileContent)
        tempFile.deleteOnExit()
        val parameters = InlineEditCompletionParameters(
            selectedText = "myTestMethod()",
            filePath = tempFile.absolutePath,
            fileExtension = "java",
            projectBasePath = project.basePath,
            referencedFiles = mutableListOf(
                ReferencedFile(
                    "TEST_FILE_NAME_1.java",
                    "/path/to/TEST_FILE_NAME_1.java",
                    "TEST_FILE_CONTENT_1"
                ),
                ReferencedFile(
                    "TEST_FILE_NAME_2.java",
                    "/path/to/TEST_FILE_NAME_2.java",
                    "TEST_FILE_CONTENT_2"
                )
            ),
            gitDiff = "TEST_GIT_DIFF",
            conversation = Conversation().apply {
                messages = mutableListOf(
                    Message("PREV_PROMPT").apply {
                        response = "PREV_RESPONSE"
                    }
                )
            },
            conversationHistory = listOf(
                Conversation().apply {
                    messages = mutableListOf(
                        Message("HISTORY_PROMPT_1").apply {
                            response = "HISTORY_RESPONSE_1"
                        }
                    )
                },
                Conversation().apply {
                    messages = mutableListOf(
                        Message("HISTORY_PROMPT_2").apply {
                            response = "HISTORY_RESPONSE_2"
                        }
                    )
                }
            ),
            diagnosticsInfo = null
        )

        val prompt = OpenAIRequestFactory().createInlineEditPrompt(parameters)

        val systemMessage = prompt.messages[0] as KoogMessage.System
        val expectedSystem = buildExpectedInlineEditSystemPrompt(
            language = "java",
            filePath = tempFile.absolutePath,
            fileContent = testFileContent,
            projectBasePath = project.basePath,
            referencedFiles = parameters.referencedFiles,
            gitDiff = parameters.gitDiff,
            conversationHistory = parameters.conversationHistory,
            diagnosticsInfo = parameters.diagnosticsInfo,
        )
        assertThat(systemMessage.content).isEqualTo(expectedSystem)
        val prevUserMessage = prompt.messages[1] as KoogMessage.User
        assertThat(prevUserMessage.content).isEqualTo("PREV_PROMPT")
        val prevResponse = prompt.messages[2] as KoogMessage.Assistant
        assertThat(prevResponse.content).isEqualTo("PREV_RESPONSE")
        val followUpMessage = prompt.messages[3] as KoogMessage.User
        assertThat(followUpMessage.content).isEqualTo("Implement.")
    }

    private fun buildExpectedInlineEditSystemPrompt(
        language: String,
        filePath: String,
        fileContent: String,
        projectBasePath: String?,
        referencedFiles: List<ReferencedFile>?,
        gitDiff: String?,
        conversationHistory: List<Conversation>?,
        diagnosticsInfo: String? = null,
    ): String {
        var systemPrompt = service<PromptsSettings>().state.coreActions.inlineEdit.instructions
            ?: CoreActionsState.DEFAULT_INLINE_EDIT_PROMPT

        if (projectBasePath != null) {
            val projectContext =
                "Project Context:\nProject root: ${projectBasePath}\nAll file paths should be relative to this project root."
            systemPrompt = systemPrompt.replace("{{PROJECT_CONTEXT}}", projectContext)
        } else {
            systemPrompt = systemPrompt.replace("\n{{PROJECT_CONTEXT}}\n", "")
        }

        val currentFileBlock = buildString {
            append("```$language:$filePath\n")
            append(fileContent)
            append("\n```")
        }
        systemPrompt = systemPrompt.replace("{{CURRENT_FILE_CONTEXT}}", currentFileBlock)

        val externalContext = buildString {
            val currentPath = filePath
            val unique = mutableSetOf<String>()
            val hasRefs = referencedFiles
                ?.filter { it.filePath() != currentPath }
                ?.any { !it.fileContent().isNullOrBlank() } == true

            if (hasRefs) {
                append("\n\n### Referenced Files")
                referencedFiles
                    .filter { it.filePath() != currentPath }
                    .forEach {
                        if (!it.fileContent().isNullOrBlank() && unique.add(it.filePath())) {
                            append("\n\n```${it.getFileExtension()}:${it.filePath()}\n")
                            append(it.fileContent())
                            append("\n```")
                        }
                    }
            }

            if (!gitDiff.isNullOrBlank()) {
                append("\n\n### Git Diff\n\n")
                append("```diff\n${gitDiff}\n```")
            }

            if (!conversationHistory.isNullOrEmpty()) {
                append("\n\n### Conversation History\n")
                conversationHistory.forEach { conversation ->
                    conversation.messages.forEach { message ->
                        val p = message.prompt?.trim().orEmpty()
                        if (p.isNotEmpty()) append("\n**User:** $p")
                        val r = message.response?.trim().orEmpty()
                        if (r.isNotEmpty()) append("\n**Assistant:** $r")
                    }
                }
            }

            if (!diagnosticsInfo.isNullOrBlank()) {
                append("\n\n### Diagnostics\n")
                append(diagnosticsInfo)
            }
        }
        systemPrompt = if (externalContext.isEmpty()) {
            systemPrompt.replace(
                "{{EXTERNAL_CONTEXT}}",
                "## External Context\n\nNo external context selected."
            )
        } else {
            systemPrompt.replace(
                "{{EXTERNAL_CONTEXT}}",
                "## External Context$externalContext"
            )
        }

        return systemPrompt
    }

    private fun normalize(messages: List<KoogMessage>): List<Map<String, Any?>> {
        return messages.map { msg ->
            when (msg) {
                is KoogMessage.System -> mapOf(
                    "role" to "system",
                    "content" to msg.content
                )

                is KoogMessage.User -> mapOf(
                    "role" to "user",
                    "content" to msg.content
                )

                is KoogMessage.Assistant -> mapOf(
                    "role" to "assistant",
                    "content" to msg.content
                )

                is KoogMessage.Tool.Call -> mapOf(
                    "role" to "tool_call",
                    "id" to msg.id,
                    "tool" to msg.tool,
                    "args" to msg.content
                )

                is KoogMessage.Tool.Result -> mapOf(
                    "role" to "tool_result",
                    "id" to msg.id,
                    "tool" to msg.tool,
                    "content" to msg.content
                )

                else -> mapOf("role" to "unknown")
            }
        }
    }
}
