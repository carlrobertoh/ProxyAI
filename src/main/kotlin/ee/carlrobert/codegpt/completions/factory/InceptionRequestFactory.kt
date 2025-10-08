package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.*
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.llm.client.inception.request.InceptionApplyRequest
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionRequest
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionStandardMessage
import ee.carlrobert.llm.completion.CompletionRequest

class InceptionRequestFactory : BaseRequestFactory() {

    override fun createChatRequest(params: ChatCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.CHAT)
        val messages = OpenAIRequestFactory.buildOpenAIMessages(
            model = model,
            callParameters = params
        )
        return OpenAIChatCompletionRequest.Builder(messages)
            .setModel(model)
            .setStream(true)
            .build()
    }

    override fun createInlineEditRequest(params: InlineEditCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.INLINE_EDIT)
        val prepared = prepareInlineEditPrompts(params)
        val messages = OpenAIRequestFactory.buildInlineEditMessages(prepared, params.conversation)
        return OpenAIChatCompletionRequest.Builder(messages)
            .setModel(model)
            .setStream(true)
            .build()
    }

    override fun createAutoApplyRequest(params: AutoApplyParameters): InceptionApplyRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.AUTO_APPLY)
        val prompt =
            "<|original_code|>\n" + EditorUtil.getFileContent(params.destination) + "\n<|/original_code|>\n\n<|update_snippet|>\n" + params.source + "\n<|/update_snippet|>"

        return InceptionApplyRequest.Builder()
            .setModel(model)
            .setMessages(
                listOf(
                    OpenAIChatCompletionStandardMessage("user", prompt)
                )
            )
            .build()
    }

    override fun createBasicCompletionRequest(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        stream: Boolean,
        featureType: FeatureType
    ): CompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(featureType)
        return OpenAIChatCompletionRequest.Builder(
            listOf(
                OpenAIChatCompletionStandardMessage("system", systemPrompt),
                OpenAIChatCompletionStandardMessage("user", userPrompt)
            )
        )
            .setModel(model)
            .setStream(stream)
            .build()
    }

    override fun createCommitMessageRequest(params: CommitMessageCompletionParameters): OpenAIChatCompletionRequest {
        val model =
            ModelSelectionService.getInstance().getModelForFeature(FeatureType.COMMIT_MESSAGE)
        val (gitDiff, systemPrompt) = params
        return OpenAIChatCompletionRequest.Builder(
            listOf(
                OpenAIChatCompletionStandardMessage("system", systemPrompt),
                OpenAIChatCompletionStandardMessage("user", gitDiff)
            )
        )
            .setModel(model)
            .setStream(true)
            .build()
    }

    override fun createLookupRequest(params: LookupCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.LOOKUP)
        val (prompt) = params
        val systemPrompt =
            service<PromptsSettings>().state.coreActions.generateNameLookups.instructions
                ?: CoreActionsState.DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT

        return OpenAIChatCompletionRequest.Builder(
            listOf(
                OpenAIChatCompletionStandardMessage("system", systemPrompt),
                OpenAIChatCompletionStandardMessage("user", prompt)
            )
        )
            .setModel(model)
            .setStream(false)
            .build()
    }
}

