package ee.carlrobert.codegpt.completions

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.readText
import ee.carlrobert.codegpt.completions.factory.*
import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState
import ee.carlrobert.codegpt.settings.prompts.FilteredPromptsService
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.llm.completion.CompletionRequest

interface CompletionRequestFactory {
    fun createChatRequest(params: ChatCompletionParameters): CompletionRequest
    fun createEditCodeRequest(params: EditCodeCompletionParameters): CompletionRequest
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
    override fun createEditCodeRequest(params: EditCodeCompletionParameters): CompletionRequest {
        val prompt = "Code to modify:\n${params.selectedText}\n\nInstructions: ${params.prompt}"
        return createBasicCompletionRequest(
            service<FilteredPromptsService>().getFilteredEditCodePrompt(params.chatMode), prompt, AUTO_APPLY_MAX_TOKENS, true, FeatureType.EDIT_CODE
        )
    }

    override fun createCommitMessageRequest(params: CommitMessageCompletionParameters): CompletionRequest {
        return createBasicCompletionRequest(params.systemPrompt, params.gitDiff, 512, true, FeatureType.COMMIT_MESSAGE)
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
        val formattedDestination = CompletionRequestUtil.formatCode(destination.readText(), destination.path)
        
        val systemPromptTemplate = service<FilteredPromptsService>().getFilteredAutoApplyPrompt(params.chatMode, params.destination)
        val systemPrompt = systemPromptTemplate
            .replace("{{changes_to_merge}}", formattedSource)
            .replace("{{destination_file}}", formattedDestination)
        
        return createBasicCompletionRequest(systemPrompt, "Merge the following changes to the destination file.", AUTO_APPLY_MAX_TOKENS, true, FeatureType.AUTO_APPLY)
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
