package ee.carlrobert.codegpt.completions

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.codecompletions.edit.NextEditPromptUtil
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.CURSOR_MARKER
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.DEFAULT_LINES_AFTER
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.DEFAULT_LINES_BEFORE
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.MAX_EDITABLE_REGION_LINES
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.MAX_RECENTLY_VIEWED_SNIPPETS
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.RECENTLY_VIEWED_LINES
import ee.carlrobert.codegpt.completions.factory.*
import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState
import ee.carlrobert.codegpt.settings.prompts.FilteredPromptsService
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.GitUtil
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.llm.completion.CompletionRequest

interface CompletionRequestFactory {
    fun createChatRequest(params: ChatCompletionParameters): CompletionRequest
    fun createInlineEditRequest(params: InlineEditCompletionParameters): CompletionRequest
    fun createAutoApplyRequest(params: AutoApplyParameters): CompletionRequest
    fun createCommitMessageRequest(params: CommitMessageCompletionParameters): CompletionRequest
    fun createLookupRequest(params: LookupCompletionParameters): CompletionRequest
    fun createNextEditRequest(params: NextEditParameters): CompletionRequest {
        throw UnsupportedOperationException("Next Edit is not supported by this provider")
    }

    companion object {
        const val CURSOR_MARKER = "<|cursor|>"
        const val DEFAULT_LINES_BEFORE = 50
        const val DEFAULT_LINES_AFTER = 50
        const val MAX_EDITABLE_REGION_LINES = 200
        const val MAX_RECENTLY_VIEWED_SNIPPETS = 3
        const val RECENTLY_VIEWED_LINES = 200

        @JvmStatic
        fun getFactory(serviceType: ServiceType): CompletionRequestFactory {
            return when (serviceType) {
                ServiceType.PROXYAI -> CodeGPTRequestFactory(ClassStructureSerializer)
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

        @JvmStatic
        fun getFactoryForFeature(featureType: FeatureType): CompletionRequestFactory {
            val serviceType = ModelSelectionService.getInstance().getServiceForFeature(featureType)
            return getFactory(serviceType)
        }
    }
}

abstract class BaseRequestFactory : CompletionRequestFactory {
    companion object {
        private const val LOOKUP_MAX_TOKENS = 512
        private const val AUTO_APPLY_MAX_TOKENS = 8192
        private const val DEFAULT_MAX_TOKENS = 4096
    }

    data class InlineEditPrompts(val systemPrompt: String, val userPrompt: String)

    protected fun prepareInlineEditPrompts(params: InlineEditCompletionParameters): InlineEditPrompts {
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
            val currentPath = filePath
            val unique = mutableSetOf<String>()
            val hasRefs = params.referencedFiles
                ?.filter { it.filePath != currentPath }
                ?.any { !it.fileContent.isNullOrBlank() } == true

            if (hasRefs) {
                append("\n\n### Referenced Files")
                params.referencedFiles
                    .filter { it.filePath != currentPath }
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

        val userPrompt = buildString {
            if (!params.selectedText.isNullOrBlank()) {
                append("Selected code:\n")
                append("```$language\n")
                append(params.selectedText)
                append("\n```\n\n")
            }
            append("Request: ${params.prompt}")
        }

        return InlineEditPrompts(systemPrompt, userPrompt)
    }

    override fun createInlineEditRequest(params: InlineEditCompletionParameters): CompletionRequest {
        val prepared = prepareInlineEditPrompts(params)
        return createBasicCompletionRequest(
            prepared.systemPrompt,
            prepared.userPrompt,
            AUTO_APPLY_MAX_TOKENS,
            true,
            FeatureType.INLINE_EDIT
        )
    }

    override fun createCommitMessageRequest(params: CommitMessageCompletionParameters): CompletionRequest {
        return createBasicCompletionRequest(
            params.systemPrompt,
            params.gitDiff,
            512,
            true,
            FeatureType.COMMIT_MESSAGE
        )
    }

    override fun createLookupRequest(params: LookupCompletionParameters): CompletionRequest {
        return createBasicCompletionRequest(
            service<PromptsSettings>().state.coreActions.generateNameLookups.instructions
                ?: CoreActionsState.DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT,
            params.prompt,
            LOOKUP_MAX_TOKENS,
            false,
            FeatureType.LOOKUP
        )
    }

    override fun createAutoApplyRequest(params: AutoApplyParameters): CompletionRequest {
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

        return createBasicCompletionRequest(
            systemPrompt,
            "Merge the following changes to the destination file.",
            AUTO_APPLY_MAX_TOKENS,
            true,
            FeatureType.AUTO_APPLY
        )
    }

    abstract fun createBasicCompletionRequest(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        stream: Boolean = false,
        featureType: FeatureType
    ): CompletionRequest

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

    protected fun composeNextEditMessage(params: NextEditParameters): String {
        val (project, fileName, filePath, fileContent, caretOffset, gitDiff, _) = params

        val encodingManager = EncodingManager.getInstance()
        val prefixContent = encodingManager.truncateText(fileContent.substring(0, caretOffset), 4096, true)
        val suffixContent = encodingManager.truncateText(fileContent.substring(caretOffset, fileContent.length), 4096, false)

        val truncatedContent = prefixContent + suffixContent
        val adjustedCaretOffset = prefixContent.length

        val regionByLines = NextEditPromptUtil.determineEditableRegionByLines(
            truncatedContent,
            adjustedCaretOffset,
            5,
            15,
            21
        )

        val startPos = regionByLines.first
        val endPos = regionByLines.second

        val prefix = truncatedContent.substring(0, startPos)
        val regionContent = truncatedContent.substring(startPos, endPos)
        val suffix = truncatedContent.substring(endPos)

        val cursorPosInRegion = (adjustedCaretOffset - startPos).coerceIn(0, regionContent.length)
        val editableWithCursor =
            regionContent.substring(0, cursorPosInRegion) + CURSOR_MARKER + regionContent.substring(
                cursorPosInRegion
            )

        val recentlyViewedBlock = NextEditPromptUtil.buildRecentlyViewedBlock(
            project,
            filePath,
            MAX_RECENTLY_VIEWED_SNIPPETS,
            RECENTLY_VIEWED_LINES
        )

        val promptBuilder = StringBuilder()
        promptBuilder.append(recentlyViewedBlock)

        promptBuilder.append("\n<|current_file_content|>\n")
        promptBuilder.append("current_file_path: ").append(fileName).append('\n')
        promptBuilder.append(prefix)
        promptBuilder.append("\n<|code_to_edit|>\n")
        promptBuilder.append(editableWithCursor)
        if (!editableWithCursor.endsWith('\n')) promptBuilder.append('\n')
        promptBuilder.append("\n<|/code_to_edit|>\n")
        promptBuilder.append(suffix)
        promptBuilder.append("\n<|/current_file_content|>\n\n")

        promptBuilder.append("<|edit_diff_history|>\n")
        val gitDiffRaw = gitDiff ?: buildEditDiffHistory(project)
        if (gitDiffRaw.isNotEmpty()) {
            promptBuilder.append(gitDiffRaw).append('\n')
        }
        promptBuilder.append("<|/edit_diff_history|>\n")

        return promptBuilder.toString()
    }

    protected fun buildEditDiffHistory(project: com.intellij.openapi.project.Project?): String {
        if (project == null) return ""
        return try {
            GitUtil.getCurrentChanges(project).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}