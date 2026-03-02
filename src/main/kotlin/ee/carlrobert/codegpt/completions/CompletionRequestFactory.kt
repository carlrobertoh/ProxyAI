package ee.carlrobert.codegpt.completions

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.PromptBuilder
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.MAX_RECENTLY_VIEWED_SNIPPETS
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.RECENTLY_VIEWED_LINES
import ee.carlrobert.codegpt.completions.factory.*
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.nextedit.NextEditPromptUtil
import ee.carlrobert.codegpt.settings.configuration.ChatMode
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState
import ee.carlrobert.codegpt.settings.prompts.FilteredPromptsService
import ee.carlrobert.codegpt.settings.prompts.PersonaDetails
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.prompts.addProjectPath
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.ui.textarea.ConversationTagProcessor
import ee.carlrobert.codegpt.util.EditWindowFormatter.FormatResult
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.GitUtil
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.codegpt.mcp.McpToolPromptFormatter

interface CompletionRequestFactory {
    fun createChatCompletionPrompt(callParameters: ChatCompletionParameters): Prompt
    fun createInlineEditPrompt(params: InlineEditCompletionParameters): Prompt
    fun createInlineEditQuestionPrompt(parameters: ChatCompletionParameters): Prompt
    fun createAutoApplyPrompt(params: AutoApplyParameters): Prompt
    fun createCommitMessagePrompt(params: CommitMessageCompletionParameters): Prompt
    fun createLookupPrompt(params: LookupCompletionParameters): Prompt
    fun createNextEditRequest(
        params: NextEditParameters,
        formatResult: FormatResult
    ): CompletionRequest {
        throw UnsupportedOperationException("Next Edit is not supported by this provider")
    }

    companion object {
        const val MAX_RECENTLY_VIEWED_SNIPPETS = 3
        const val RECENTLY_VIEWED_LINES = 200

        @JvmStatic
        fun getFactory(serviceType: ServiceType): CompletionRequestFactory {
            return when (serviceType) {
                ServiceType.PROXYAI -> CodeGPTRequestFactory()
                ServiceType.OPENAI -> OpenAIRequestFactory()
                ServiceType.CUSTOM_OPENAI -> CustomOpenAIRequestFactory()
                ServiceType.ANTHROPIC -> ClaudeRequestFactory()
                ServiceType.GOOGLE -> GoogleRequestFactory()
                ServiceType.MISTRAL -> MistralRequestFactory()
                ServiceType.OLLAMA -> OllamaRequestFactory()
                ServiceType.LLAMA_CPP -> LlamaRequestFactory()
                ServiceType.INCEPTION -> InceptionRequestFactory()
            }
        }
    }
}

abstract class BaseRequestFactory : CompletionRequestFactory {

    override fun createChatCompletionPrompt(callParameters: ChatCompletionParameters): Prompt {
        val systemPrompt = buildKoogSystemPrompt(callParameters)
        val currentMessage = callParameters.message

        return prompt("chat-completion") {
            if (systemPrompt.isNotBlank()) {
                system(systemPrompt)
            }

            val currentMessageId = currentMessage.id
            callParameters.conversation.messages.forEach { msg ->
                val isCurrent = msg.id == currentMessageId
                if (isCurrent && callParameters.retry) {
                    return@forEach
                }
                if (isCurrent) {
                    return@forEach
                }

                appendUserPrompt(msg.prompt)
                appendAssistantAndToolState(msg)
            }

            when (callParameters.requestType) {
                RequestType.NORMAL_REQUEST -> {
                    appendCurrentUserMessage(callParameters)
                }

                RequestType.TOOL_CALL_REQUEST -> {
                    appendCurrentUserMessage(callParameters)
                    appendAssistantAndToolState(currentMessage)
                }

                RequestType.TOOL_CALL_CONTINUATION -> {
                    appendCurrentUserMessage(callParameters)
                    appendAssistantAndToolState(currentMessage, includeToolResults = false)
                    currentMessage.toolCallResults?.forEach { (callId, result) ->
                        val toolName = currentMessage.toolCalls
                            ?.firstOrNull { it.id == callId }
                            ?.function
                            ?.name
                            ?: "unknown-function"
                        tool { result(callId, toolName, result) }
                    }
                    if (!callParameters.retry && !currentMessage.response.isNullOrBlank()) {
                        assistant(currentMessage.response)
                    }
                }
            }
        }
    }

    private fun PromptBuilder.appendCurrentUserMessage(
        callParameters: ChatCompletionParameters
    ) {
        val promptWithContext = getPromptWithFilesContext(callParameters)
        val imageDetails = callParameters.imageDetails
        if (imageDetails != null) {
            val format = imageDetails.mediaType
                .substringAfter('/')
                .substringBefore(';')
                .ifBlank { "png" }
            val imagePart = ContentPart.Image(
                content = AttachmentContent.Binary.Bytes(imageDetails.data),
                format = format,
                mimeType = imageDetails.mediaType
            )
            val parts = buildList {
                add(imagePart)
                if (promptWithContext.isNotBlank()) {
                    add(ContentPart.Text(promptWithContext))
                }
            }
            user(parts)
            return
        }

        appendUserPrompt(promptWithContext)
    }

    private fun PromptBuilder.appendUserPrompt(prompt: String?) {
        if (!prompt.isNullOrBlank()) {
            user(prompt)
        }
    }

    private fun PromptBuilder.appendAssistantAndToolState(
        message: Message,
        includeToolResults: Boolean = true
    ) {
        if (message.hasToolCalls()) {
            if (!message.response.isNullOrBlank()) {
                assistant(message.response)
            }
            message.toolCalls?.forEach { toolCall ->
                tool {
                    call(
                        toolCall.id,
                        toolCall.function.name ?: "unknown-function",
                        toolCall.function.arguments
                            ?.takeIf { it.isNotBlank() }
                            ?: "{}"
                    )
                }
            }
            if (includeToolResults) {
                message.toolCallResults?.forEach { (callId, result) ->
                    val toolName = message.toolCalls
                        ?.firstOrNull { it.id == callId }
                        ?.function
                        ?.name
                        ?: "unknown-function"
                    tool { result(callId, toolName, result) }
                }
            }
        } else if (!message.response.isNullOrBlank()) {
            assistant(message.response)
        }
    }

    private fun buildKoogSystemPrompt(callParameters: ChatCompletionParameters): String {
        val promptsSettings = service<PromptsSettings>().state
        val filteredPrompts = service<FilteredPromptsService>()
        val systemParts = mutableListOf<String>()

        when (callParameters.conversationType) {
            ConversationType.DEFAULT -> {
                val selectedPersona = promptsSettings.personas.selectedPersona
                if (!selectedPersona.disabled) {
                    val baseInstructions = callParameters.personaDetails?.instructions?.addProjectPath()
                        ?: filteredPrompts
                            .getFilteredPersonaPrompt(callParameters.chatMode)
                            .addProjectPath()
                    val clickableInstructions = filteredPrompts.applyClickableLinks(baseInstructions)
                    if (clickableInstructions.isNotBlank()) {
                        systemParts.add(clickableInstructions)
                    }
                }
            }

            ConversationType.REVIEW_CHANGES -> {
                promptsSettings.coreActions.reviewChanges.instructions
                    ?.takeIf { it.isNotBlank() }
                    ?.let(systemParts::add)
            }

            ConversationType.FIX_COMPILE_ERRORS -> {
                promptsSettings.coreActions.fixCompileErrors.instructions
                    ?.takeIf { it.isNotBlank() }
                    ?.let(systemParts::add)
            }

            else -> Unit
        }

        val history = callParameters.history
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { ConversationTagProcessor.formatConversation(it) }
            .orEmpty()
        if (history.isNotBlank()) {
            systemParts.add("Conversation history:\n$history")
        }

        if (!callParameters.mcpTools.isNullOrEmpty() &&
            callParameters.toolApprovalMode != ToolApprovalMode.BLOCK_ALL
        ) {
            val toolsPrompt = McpToolPromptFormatter().formatToolsForSystemPrompt(callParameters.mcpTools!!)
            if (toolsPrompt.isNotBlank()) {
                systemParts.add(toolsPrompt)
            }
        }

        return systemParts.joinToString("\n\n").trim()
    }

    override fun createInlineEditQuestionPrompt(parameters: ChatCompletionParameters): Prompt {
        return createChatCompletionPrompt(buildInlineEditQuestionParams(parameters))
    }

    private fun buildInlineEditQuestionParams(parameters: ChatCompletionParameters): ChatCompletionParameters {
        val systemPrompt = """
            You are an Inline Edit assistant for a single open file.
            Respond in two parts:

            1) Explanation (concise):
               - 3–5 short bullets max.
               - Summarize what will change and why.
               - Reference functions/classes by name. Do not paste full files.

            2) Update Snippet(s):
               - Provide ONLY partial changes as one or more fenced code blocks using triple backticks with the correct language (```python, ```kotlin, etc.).
               - Do NOT include any special tags.
               - Use minimal necessary context; indicate gaps with language-appropriate comments like "// ... existing code ..." or "# ... existing code ...".
               - Include only changed/new lines with at most 1–3 lines of surrounding context when needed.
               - Prefer stable anchors (function/class signatures, imports) to locate insertion points.
               - Never output entire files or unrelated edits.
        """.trimIndent()

        val userPrompt = getPromptWithFilesContext(parameters)

        val newParams = ChatCompletionParameters
            .builder(parameters.conversation, Message(userPrompt))
            .sessionId(parameters.sessionId)
            .conversationType(parameters.conversationType)
            .retry(parameters.retry)
            .imageDetails(parameters.imageDetails)
            .history(parameters.history)
            .referencedFiles(parameters.referencedFiles)
            .personaDetails(PersonaDetails(-1L, "Inline Edit Guidance", systemPrompt))
            .psiStructure(parameters.psiStructure)
            .project(parameters.project)
            .chatMode(ChatMode.ASK)
            .featureType(FeatureType.INLINE_EDIT)
            .build()

        return newParams
    }

    protected fun prepareInlineEditSystemPrompt(params: InlineEditCompletionParameters): String {
        val language = params.fileExtension ?: "txt"
        val filePath = params.filePath ?: "untitled"
        var systemPrompt =
            service<PromptsSettings>().state.coreActions.inlineEdit.instructions
                ?: CoreActionsState.DEFAULT_INLINE_EDIT_PROMPT

        if (params.projectBasePath != null) {
            val projectContext =
                "Project Context:\nProject root: ${params.projectBasePath}\nAll file paths should be relative to this project root."
            systemPrompt = systemPrompt.replace("{{PROJECT_CONTEXT}}", projectContext)
        } else {
            systemPrompt = systemPrompt.replace("\n{{PROJECT_CONTEXT}}\n", "")
        }

        val currentFileContent = try {
            params.filePath?.let { filePath ->
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                virtualFile?.let { EditorUtil.getFileContent(it) }
            }
        } catch (_: Throwable) {
            null
        }
        val currentFileBlock = buildString {
            append("```$language:$filePath\n")
            append(currentFileContent ?: "")
            append("\n```")
        }
        systemPrompt = systemPrompt.replace("{{CURRENT_FILE_CONTEXT}}", currentFileBlock)

        val externalContext = buildString {
            val unique = mutableSetOf<String>()
            val hasRefs = params.referencedFiles
                ?.filter { it.filePath != filePath }
                ?.any { !it.fileContent.isNullOrBlank() } == true

            if (hasRefs) {
                append("\n\n### Referenced Files")
                params.referencedFiles
                    .filter { it.filePath != filePath }
                    .forEach {
                        if (!it.fileContent.isNullOrBlank() && unique.add(it.filePath)) {
                            append("\n\n```${it.fileExtension}:${it.filePath}\n")
                            append(it.fileContent)
                            append("\n```")
                        }
                    }
            }

            if (!params.gitDiff.isNullOrBlank()) {
                append("\n\n### Git Diff\n\n")
                append("```diff\n${params.gitDiff}\n```")
            }

            if (!params.conversationHistory.isNullOrEmpty()) {
                append("\n\n### Conversation History\n")
                params.conversationHistory.forEach { conversation ->
                    conversation.messages.forEach { message ->
                        if (!message.prompt.isNullOrBlank()) {
                            append("\n**User:** ${message.prompt.trim()}")
                        }
                        if (!message.response.isNullOrBlank()) {
                            append("\n**Assistant:** ${message.response.trim()}")
                        }
                    }
                }
            }

            if (!params.diagnosticsInfo.isNullOrBlank()) {
                append("\n\n### Diagnostics\n")
                append(params.diagnosticsInfo)
            }
        }
        return if (externalContext.isEmpty()) {
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
    }

    override fun createInlineEditPrompt(params: InlineEditCompletionParameters): Prompt {
        val systemPrompt = prepareInlineEditSystemPrompt(params)
        return prompt("inline-edit-completion") {
            if (systemPrompt.isNotBlank()) {
                system(systemPrompt)
            }

            params.conversation?.messages?.forEach { message ->
                appendUserPrompt(message.prompt)
                if (!message.response.isNullOrBlank()) {
                    assistant(message.response)
                }
            }

            user("Implement.")
        }
    }

    override fun createCommitMessagePrompt(params: CommitMessageCompletionParameters): Prompt {
        return createBasicPrompt(params.systemPrompt, params.gitDiff, "commit-message-completion")
    }

    override fun createLookupPrompt(params: LookupCompletionParameters): Prompt {
        return createBasicPrompt(
            service<PromptsSettings>().state.coreActions.generateNameLookups.instructions
                ?: CoreActionsState.DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT,
            params.prompt,
            "lookup-completion"
        )
    }

    override fun createAutoApplyPrompt(params: AutoApplyParameters): Prompt {
        val destination = params.destination
        val language = FileUtil.getFileExtension(destination.path)

        val formattedSource = CompletionRequestUtil.formatCodeWithLanguage(params.source, language)
        val formattedDestination =
            CompletionRequestUtil.formatCode(
                EditorUtil.getFileContent(destination),
                destination.path
            )

        val systemPromptTemplate = service<FilteredPromptsService>().getFilteredAutoApplyPrompt(
            params.chatMode,
            params.destination
        )
        val systemPrompt = systemPromptTemplate
            .replace("{{changes_to_merge}}", formattedSource)
            .replace("{{destination_file}}", formattedDestination)

        return createBasicPrompt(
            systemPrompt,
            "Merge the following changes to the destination file.",
            "auto-apply-completion"
        )
    }

    protected fun createBasicPrompt(
        systemPrompt: String,
        userPrompt: String,
        name: String = "completion"
    ): Prompt {
        return prompt(name) {
            if (systemPrompt.isNotBlank()) {
                system(systemPrompt)
            }
            if (userPrompt.isNotBlank()) {
                user(userPrompt)
            }
        }
    }

    protected fun getPromptWithFilesContext(callParameters: ChatCompletionParameters): String {
        return callParameters.referencedFiles?.let {
            if (it.isEmpty()) {
                callParameters.message.prompt
            } else {
                CompletionRequestUtil.getPromptWithContext(
                    it,
                    callParameters.message.prompt,
                    callParameters.psiStructure,
                )
            }
        } ?: return callParameters.message.prompt
    }

    protected fun composeNextEditMessage(
        params: NextEditParameters,
        formatResult: FormatResult
    ): String {
        val (project) = params
        val recentlyViewedBlock = NextEditPromptUtil.buildRecentlyViewedBlock(
            project,
            params.filePath,
            MAX_RECENTLY_VIEWED_SNIPPETS,
            RECENTLY_VIEWED_LINES
        )

        val promptBuilder = StringBuilder()
        promptBuilder.append(recentlyViewedBlock)

        promptBuilder.append("\n").append(formatResult.formattedContent).append("\n\n")

        promptBuilder.append("<|edit_diff_history|>\n")
        val gitDiffRaw = params.gitDiff ?: buildEditDiffHistory(project)
        if (gitDiffRaw.isNotEmpty()) {
            promptBuilder.append(gitDiffRaw).append('\n')
        }
        promptBuilder.append("<|/edit_diff_history|>\n")

        return promptBuilder.toString()
    }

    protected fun buildEditDiffHistory(project: Project?): String {
        if (project == null) return ""
        return try {
            GitUtil.getCurrentChanges(project).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
