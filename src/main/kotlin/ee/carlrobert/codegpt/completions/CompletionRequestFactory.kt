package ee.carlrobert.codegpt.completions

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.readText
import ee.carlrobert.codegpt.completions.factory.*
import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState
import ee.carlrobert.codegpt.settings.prompts.FilteredPromptsService
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.llm.completion.CompletionRequest

interface CompletionRequestFactory {
    fun createChatRequest(params: ChatCompletionParameters): CompletionRequest
    fun createInlineEditRequest(params: InlineEditCompletionParameters): CompletionRequest
    fun createAutoApplyRequest(params: AutoApplyParameters): CompletionRequest
    fun createCommitMessageRequest(params: CommitMessageCompletionParameters): CompletionRequest
    fun createLookupRequest(params: LookupCompletionParameters): CompletionRequest

    companion object {
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
            CompletionRequestUtil.formatCode(EditorUtil.getFileContent(destination), destination.path)

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
}
