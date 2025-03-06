package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.completions.CommitMessageCompletionParameters
import ee.carlrobert.codegpt.completions.CompletionRequestFactory
import ee.carlrobert.codegpt.completions.CompletionRequestUtil
import ee.carlrobert.codegpt.completions.ConversationType
import ee.carlrobert.codegpt.completions.EditCodeCompletionParameters
import ee.carlrobert.codegpt.completions.LookupCompletionParameters
import ee.carlrobert.codegpt.completions.TotalUsageExceededException
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings.Companion.getState
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.codegpt.util.file.FileUtil.getImageMediaType
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel.O_1_MINI
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel.O_1_PREVIEW
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel.O_3_MINI
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel.findByCode
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionDetailedMessage
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionMessage
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionRequest
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionStandardMessage
import ee.carlrobert.llm.client.openai.completion.request.OpenAIImageUrl
import ee.carlrobert.llm.client.openai.completion.request.OpenAIMessageImageURLContent
import ee.carlrobert.llm.client.openai.completion.request.OpenAIMessageTextContent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class OpenAIRequestFactory : CompletionRequestFactory {

    override fun createChatRequest(params: ChatCompletionParameters): OpenAIChatCompletionRequest {
        val model = service<OpenAISettings>().state.model
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
        val model = service<OpenAISettings>().state.model
        val prompt = "Code to modify:\n${params.selectedText}\n\nInstructions: ${params.prompt}"
        val systemPrompt = service<PromptsSettings>().state.coreActions.editCode.instructions
            ?: CoreActionsState.DEFAULT_EDIT_CODE_PROMPT
        if (isReasoningModel(model)) {
            return buildBasicO1Request(model, prompt, systemPrompt, stream = true)
        }
        return createBasicCompletionRequest(systemPrompt, prompt, model, true)
    }

    override fun createCommitMessageRequest(params: CommitMessageCompletionParameters): OpenAIChatCompletionRequest {
        val model = service<OpenAISettings>().state.model
        val (gitDiff, systemPrompt) = params
        if (isReasoningModel(model)) {
            return buildBasicO1Request(model, gitDiff, systemPrompt, stream = true)
        }
        return createBasicCompletionRequest(systemPrompt, gitDiff, model, true)
    }

    override fun createLookupRequest(params: LookupCompletionParameters): OpenAIChatCompletionRequest {
        val model = service<OpenAISettings>().state.model
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
            listOf(O_3_MINI.code, O_1_MINI.code, O_1_PREVIEW.code).contains(model)

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
            psiStructure: Set<ClassStructure>? = null
        ): List<OpenAIChatCompletionMessage> {
            val messages = buildOpenAIChatMessages(
                model = model,
                callParameters = callParameters,
                referencedFiles = referencedFiles ?: callParameters.referencedFiles,
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
            psiStructure: Set<ClassStructure>? = null
        ): MutableList<OpenAIChatCompletionMessage> {
            val message = callParameters.message
            val messages = mutableListOf<OpenAIChatCompletionMessage>()
            val role = if (isReasoningModel(model)) "user" else "system"

            val selectedPersona = service<PromptsSettings>().state.personas.selectedPersona
            if (callParameters.conversationType == ConversationType.DEFAULT && !selectedPersona.disabled) {
                val sessionPersonaDetails = callParameters.personaDetails
                if (sessionPersonaDetails == null) {
                    messages.add(
                        OpenAIChatCompletionStandardMessage(role, selectedPersona.instructions)
                    )
                } else {
                    messages.add(
                        OpenAIChatCompletionStandardMessage(
                            role,
                            sessionPersonaDetails.instructions
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
