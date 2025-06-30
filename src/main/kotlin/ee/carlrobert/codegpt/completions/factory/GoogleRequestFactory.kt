package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.completions.BaseRequestFactory
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.completions.ConversationType
import ee.carlrobert.codegpt.completions.TotalUsageExceededException
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.prompts.FilteredPromptsService
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.google.GoogleSettings
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.llm.client.google.completion.GoogleCompletionContent
import ee.carlrobert.llm.client.google.completion.GoogleCompletionRequest
import ee.carlrobert.llm.client.google.completion.GoogleContentPart
import ee.carlrobert.llm.client.google.completion.GoogleGenerationConfig
import ee.carlrobert.llm.client.google.models.GoogleModel
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class GoogleRequestFactory : BaseRequestFactory() {

    override fun createChatRequest(params: ChatCompletionParameters): GoogleCompletionRequest {
        val configuration = service<ConfigurationSettings>().state
        val messages = buildGoogleMessages(service<GoogleSettings>().state.model, params)
        return GoogleCompletionRequest.Builder(messages)
            .generationConfig(
                GoogleGenerationConfig.Builder()
                    .maxOutputTokens(configuration.maxTokens)
                    .temperature(configuration.temperature.toDouble()).build()
            )
            .systemInstruction(buildSystemInstruction(params))
            .build()
    }

    override fun createBasicCompletionRequest(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        stream: Boolean
    ): GoogleCompletionRequest {
        val configuration = service<ConfigurationSettings>().state
        return GoogleCompletionRequest.Builder(
            listOf(
                GoogleCompletionContent("user", listOf(systemPrompt)),
                GoogleCompletionContent("model", listOf("Understood.")),
                GoogleCompletionContent("user", listOf(userPrompt))
            )
        )
            .generationConfig(
                GoogleGenerationConfig.Builder()
                    .maxOutputTokens(maxTokens)
                    .temperature(configuration.temperature.toDouble()).build()
            )
            .build()
    }

    private fun buildGoogleMessages(
        model: String?,
        params: ChatCompletionParameters
    ): List<GoogleCompletionContent> {
        val messages = buildGoogleMessages(params)

        if (model == null) {
            return messages
        }

        val encodingManager = service<EncodingManager>()
        val totalUsage = messages.parallelStream()
            .mapToInt { message ->
                encodingManager.countMessageTokens(
                    message.role,
                    message.parts.joinToString(",") { it.text ?: "" }
                )
            }
            .sum() + service<ConfigurationSettings>().state.maxTokens

        return GoogleModel.findByCode(model)?.let { googleModel ->
            if (totalUsage <= googleModel.maxTokens) {
                messages
            } else {
                tryReducingGoogleMessagesOrThrow(
                    messages,
                    params.conversation.isDiscardTokenLimit,
                    totalUsage,
                    googleModel.maxTokens
                )
            }
        } ?: messages
    }

    private fun buildGoogleMessages(params: ChatCompletionParameters): List<GoogleCompletionContent> {
        val message = params.message
        val messages = mutableListOf<GoogleCompletionContent>()

        for (prevMessage in params.conversation.messages) {
            if (params.retry && prevMessage.id == message.id) {
                break
            }

            prevMessage.imageFilePath?.takeIf { it.isNotEmpty() }?.let { imagePath ->
                try {
                    val imageData = Files.readAllBytes(Path.of(imagePath))
                    val imageMediaType =
                        FileUtil.getImageMediaType(Path.of(imagePath).fileName.toString())
                    messages.add(
                        GoogleCompletionContent(
                            listOf(
                                GoogleContentPart(
                                    null,
                                    GoogleContentPart.Blob(imageMediaType, imageData)
                                ),
                                GoogleContentPart(prevMessage.prompt)
                            ), "user"
                        )
                    )
                } catch (e: IOException) {
                    throw RuntimeException(e)
                }
            } ?: messages.add(GoogleCompletionContent("user", listOf(prevMessage.prompt)))

            messages.add(GoogleCompletionContent("model", listOf(prevMessage.response)))
        }

        if (params.imageDetails != null) {
            messages.add(
                GoogleCompletionContent(
                    listOf(
                        GoogleContentPart(
                            null,
                            GoogleContentPart.Blob(
                                params.imageDetails!!.mediaType,
                                params.imageDetails!!.data
                            )
                        ),
                        GoogleContentPart(message.prompt)
                    ), "user"
                )
            )
        } else {
            messages.add(
                GoogleCompletionContent(
                    "user",
                    listOf(getPromptWithFilesContext(params))
                )
            )
        }

        return messages
    }

    private fun tryReducingGoogleMessagesOrThrow(
        messages: List<GoogleCompletionContent>,
        discardTokenLimit: Boolean,
        totalUsage: Int,
        modelMaxTokens: Int
    ): List<GoogleCompletionContent> {
        if (!service<ConversationsState>().state!!.discardAllTokenLimits) {
            if (!discardTokenLimit) {
                throw TotalUsageExceededException()
            }
        }

        val encodingManager = EncodingManager.getInstance()
        var currentUsage = totalUsage

        // skip the system prompt
        val updatedMessages = messages.mapIndexed { index, message ->
            if (index == 0 || currentUsage <= modelMaxTokens) {
                message
            } else {
                currentUsage -= encodingManager.countMessageTokens(
                    message.role,
                    message.parts.joinToString(",") { it.text }
                )
                null
            }
        }

        return updatedMessages.filterNotNull()
    }

    private fun buildSystemInstruction(params: ChatCompletionParameters): String? {
        return when (params.conversationType) {
            ConversationType.DEFAULT -> {
                val selectedPersona = service<PromptsSettings>().state.personas.selectedPersona
                return if (!selectedPersona.disabled) {
                    service<FilteredPromptsService>().getFilteredPersonaPrompt(params.chatMode)
                } else {
                    null
                }
            }

            ConversationType.FIX_COMPILE_ERRORS -> service<PromptsSettings>().state.coreActions.fixCompileErrors.instructions

            ConversationType.REVIEW_CHANGES -> service<PromptsSettings>().state.coreActions.reviewChanges.instructions

            else -> null
        }
    }
}
