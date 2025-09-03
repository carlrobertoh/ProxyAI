package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.readText
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.completions.*
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings.Companion.getState
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState
import ee.carlrobert.codegpt.settings.prompts.FilteredPromptsService
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.prompts.addProjectPath
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.ui.textarea.ConversationTagProcessor
import ee.carlrobert.codegpt.util.file.FileUtil.getImageMediaType
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel.*
import ee.carlrobert.llm.client.openai.completion.request.*
import ee.carlrobert.llm.completion.CompletionRequest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class OpenAIRequestFactory : CompletionRequestFactory {

    override fun createChatRequest(params: ChatCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.CHAT)
        val configuration = service<ConfigurationSettings>().state
        val requestBuilder: OpenAIChatCompletionRequest.Builder =
            OpenAIChatCompletionRequest.Builder(buildOpenAIMessages(model, params))
                .setModel(model)
                .setStream(true)
                .setMaxTokens(null)
                .setMaxCompletionTokens(configuration.maxTokens)
        if (isReasoningModel(model)) {
            requestBuilder
                .setTemperature(null)
                .setPresencePenalty(null)
                .setFrequencyPenalty(null)
        } else {
            requestBuilder.setTemperature(configuration.temperature.toDouble())
        }
        return requestBuilder.build()
    }

    override fun createEditCodeRequest(params: EditCodeCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.EDIT_CODE)
        val prompt = "Code to modify:\n${params.selectedText}\n\nInstructions: ${params.prompt}"
        val systemPrompt =
            service<FilteredPromptsService>().getFilteredEditCodePrompt(params.chatMode)
        if (isReasoningModel(model)) {
            return buildBasicO1Request(model, prompt, systemPrompt, stream = true)
        }
        return createBasicCompletionRequest(systemPrompt, prompt, model, true)
    }

    override fun createAutoApplyRequest(params: AutoApplyParameters): CompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.AUTO_APPLY)
        val systemPrompt =
            service<FilteredPromptsService>().getFilteredAutoApplyPrompt(params.chatMode)
                .replace("{{changes_to_merge}}", CompletionRequestUtil.formatCode(params.source))
                .replace(
                    "{{destination_file}}",
                    CompletionRequestUtil.formatCode(
                        params.destination.readText(),
                        params.destination.path
                    )
                )
        val prompt = "Merge the following changes to the destination file."

        if (isReasoningModel(model)) {
            return buildBasicO1Request(model, prompt, systemPrompt, stream = true)
        }
        return createBasicCompletionRequest(systemPrompt, prompt, model, true)
    }

    override fun createCommitMessageRequest(params: CommitMessageCompletionParameters): OpenAIChatCompletionRequest {
        val model =
            ModelSelectionService.getInstance().getModelForFeature(FeatureType.COMMIT_MESSAGE)
        val (gitDiff, systemPrompt) = params
        if (isReasoningModel(model)) {
            return buildBasicO1Request(model, gitDiff, systemPrompt, stream = true)
        }
        return createBasicCompletionRequest(systemPrompt, gitDiff, model, true)
    }

    override fun createLookupRequest(params: LookupCompletionParameters): OpenAIChatCompletionRequest {
        val model = ModelSelectionService.getInstance().getModelForFeature(FeatureType.LOOKUP)
        val (prompt) = params
        if (isReasoningModel(model)) {
            return buildBasicO1Request(
                model,
                prompt,
                service<PromptsSettings>().state.coreActions.generateNameLookups.instructions
                    ?: CoreActionsState.DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT
            )
        }
        return createBasicCompletionRequest(
            service<PromptsSettings>().state.coreActions.generateNameLookups.instructions
                ?: CoreActionsState.DEFAULT_GENERATE_NAME_LOOKUPS_PROMPT, prompt, model
        )
    }

    companion object {
        fun isReasoningModel(model: String?) =
            listOf(
                O_4_MINI.code,
                O_3.code,
                O_3_MINI.code,
                O_1_MINI.code,
                O_1_PREVIEW.code,
                GPT_5_MINI.code,
                GPT_5.code,
            ).contains(model)

        fun buildBasicO1Request(
            model: String,
            prompt: String,
            systemPrompt: String = "",
            maxCompletionTokens: Int = 4096,
            stream: Boolean = false,
        ): OpenAIChatCompletionRequest {
            val messages = if (systemPrompt.isEmpty()) {
                listOf(OpenAIChatCompletionStandardMessage("user", prompt))
            } else {
                listOf(
                    OpenAIChatCompletionStandardMessage("user", systemPrompt),
                    OpenAIChatCompletionStandardMessage("user", prompt)
                )
            }
            return OpenAIChatCompletionRequest.Builder(messages)
                .setModel(model)
                .setMaxCompletionTokens(maxCompletionTokens)
                .setMaxTokens(null)
                .setStream(stream)
                .setTemperature(null)
                .setFrequencyPenalty(null)
                .setPresencePenalty(null)
                .build()
        }

        fun buildOpenAIMessages(
            model: String?,
            callParameters: ChatCompletionParameters,
            referencedFiles: List<ReferencedFile>? = null,
            conversationsHistory: List<Conversation>? = null,
            psiStructure: Set<ClassStructure>? = null
        ): List<OpenAIChatCompletionMessage> {
            val messages = buildOpenAIChatMessages(
                model = model,
                callParameters = callParameters,
                referencedFiles = referencedFiles ?: callParameters.referencedFiles,
                conversationsHistory = conversationsHistory ?: callParameters.history,
                psiStructure = psiStructure,
            )

            if (model == null) {
                return messages
            }

            val encodingManager = EncodingManager.getInstance()
            val totalUsage = messages.parallelStream()
                .mapToInt { message: OpenAIChatCompletionMessage? ->
                    encodingManager.countMessageTokens(
                        message
                    )
                }
                .sum() + getState().maxTokens
            val modelMaxTokens: Int
            try {
                modelMaxTokens = findByCode(model).maxTokens

                if (totalUsage <= modelMaxTokens) {
                    return messages
                }
            } catch (ex: NoSuchElementException) {
                return messages
            }
            return tryReducingMessagesOrThrow(
                messages,
                callParameters.conversation.isDiscardTokenLimit,
                totalUsage,
                modelMaxTokens
            )
        }

        private fun buildOpenAIChatMessages(
            model: String?,
            callParameters: ChatCompletionParameters,
            referencedFiles: List<ReferencedFile>? = null,
            conversationsHistory: List<Conversation>? = null,
            psiStructure: Set<ClassStructure>? = null
        ): MutableList<OpenAIChatCompletionMessage> {
            val message = callParameters.message
            val messages = mutableListOf<OpenAIChatCompletionMessage>()
            val role = if (isReasoningModel(model)) "user" else "system"

            val selectedPersona = service<PromptsSettings>().state.personas.selectedPersona
            if (callParameters.conversationType == ConversationType.DEFAULT && !selectedPersona.disabled) {
                val sessionPersonaDetails = callParameters.personaDetails
                val instructions = sessionPersonaDetails?.instructions?.addProjectPath()
                    ?: service<FilteredPromptsService>().getFilteredPersonaPrompt(callParameters.chatMode)
                        .addProjectPath()
                val history = if (conversationsHistory.isNullOrEmpty()) {
                    ""
                } else {
                    conversationsHistory.joinToString("\n\n") {
                        ConversationTagProcessor.formatConversation(it)
                    }
                }

                if (instructions.isNotEmpty()) {
                    messages.add(
                        OpenAIChatCompletionStandardMessage(
                            role,
                            instructions + "\n" + history
                        )
                    )
                }
            }
            if (callParameters.conversationType == ConversationType.REVIEW_CHANGES) {
                messages.add(
                    OpenAIChatCompletionStandardMessage(
                        role,
                        service<PromptsSettings>().state.coreActions.reviewChanges.instructions
                    )
                )
            }
            if (callParameters.conversationType == ConversationType.FIX_COMPILE_ERRORS) {
                messages.add(
                    OpenAIChatCompletionStandardMessage(
                        role,
                        service<PromptsSettings>().state.coreActions.fixCompileErrors.instructions
                    )
                )
            }

            for (prevMessage in callParameters.conversation.messages) {
                if (callParameters.retry && prevMessage.id == message.id) {
                    break
                }
                val prevMessageImageFilePath = prevMessage.imageFilePath
                if (!prevMessageImageFilePath.isNullOrEmpty()) {
                    try {
                        val imageFilePath = Path.of(prevMessageImageFilePath)
                        val imageData = Files.readAllBytes(imageFilePath)
                        val imageMediaType = getImageMediaType(imageFilePath.fileName.toString())
                        messages.add(
                            OpenAIChatCompletionDetailedMessage(
                                "user",
                                listOf(
                                    OpenAIMessageImageURLContent(
                                        OpenAIImageUrl(
                                            imageMediaType,
                                            imageData
                                        )
                                    ),
                                    OpenAIMessageTextContent(prevMessage.prompt)
                                )
                            )
                        )
                    } catch (e: IOException) {
                        throw RuntimeException(e)
                    }
                } else {
                    messages.add(OpenAIChatCompletionStandardMessage("user", prevMessage.prompt))
                }

                var response = prevMessage.response ?: ""
                if (response.startsWith("<think>")) {
                    response = response
                        .replace("(?s)<think>.*?</think>".toRegex(), "")
                        .trim { it <= ' ' }
                }

                messages.add(
                    OpenAIChatCompletionStandardMessage("assistant", response)
                )
            }

            if (callParameters.imageDetails != null) {
                messages.add(
                    OpenAIChatCompletionDetailedMessage(
                        "user",
                        listOf(
                            OpenAIMessageImageURLContent(
                                OpenAIImageUrl(
                                    callParameters.imageDetails!!.mediaType,
                                    callParameters.imageDetails!!.data
                                )
                            ),
                            OpenAIMessageTextContent(message.prompt)
                        )
                    )
                )
            } else {
                val prompt = if (referencedFiles.isNullOrEmpty()) {
                    message.prompt
                } else {
                    CompletionRequestUtil.getPromptWithContext(
                        referencedFiles,
                        message.prompt,
                        psiStructure
                    )
                }
                messages.add(OpenAIChatCompletionStandardMessage("user", prompt))
            }
            return messages
        }

        private fun tryReducingMessagesOrThrow(
            messages: MutableList<OpenAIChatCompletionMessage>,
            discardTokenLimit: Boolean,
            totalInputUsage: Int,
            modelMaxTokens: Int
        ): List<OpenAIChatCompletionMessage> {
            val result: MutableList<OpenAIChatCompletionMessage?> = messages.toMutableList()
            var totalUsage = totalInputUsage
            if (!ConversationsState.getInstance().discardAllTokenLimits) {
                if (!discardTokenLimit) {
                    throw TotalUsageExceededException()
                }
            }
            val encodingManager = EncodingManager.getInstance()
            // skip the system prompt
            for (i in 1 until result.size - 1) {
                if (totalUsage <= modelMaxTokens) {
                    break
                }

                val message = result[i]
                if (message is OpenAIChatCompletionStandardMessage) {
                    totalUsage -= encodingManager.countMessageTokens(message)
                    result[i] = null
                }
            }

            return result.filterNotNull()
        }

        fun createBasicCompletionRequest(
            systemPrompt: String,
            userPrompt: String,
            model: String? = null,
            isStream: Boolean = false
        ): OpenAIChatCompletionRequest {
            return OpenAIChatCompletionRequest.Builder(
                listOf(
                    OpenAIChatCompletionStandardMessage("system", systemPrompt),
                    OpenAIChatCompletionStandardMessage("user", userPrompt)
                )
            )
                .setModel(model)
                .setStream(isStream)
                .build()
        }
    }
}
