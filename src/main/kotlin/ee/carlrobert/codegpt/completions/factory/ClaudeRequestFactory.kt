package ee.carlrobert.codegpt.completions.factory

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.BaseRequestFactory
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.prompts.FilteredPromptsService
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.service.anthropic.AnthropicSettings
import ee.carlrobert.llm.client.anthropic.completion.*
import ee.carlrobert.llm.completion.CompletionRequest

class ClaudeRequestFactory : BaseRequestFactory() {

    override fun createChatRequest(params: ChatCompletionParameters): ClaudeCompletionRequest {
        return ClaudeCompletionRequest().apply {
            model = service<AnthropicSettings>().state.model
            maxTokens = service<ConfigurationSettings>().state.maxTokens
            isStream = true

            val selectedPersona = service<PromptsSettings>().state.personas.selectedPersona
            if (!selectedPersona.disabled) {
                system = service<FilteredPromptsService>().getFilteredPersonaPrompt(params.chatMode)
            }

            messages = params.conversation.messages
                .filter { it.response != null && it.response.isNotEmpty() }
                .flatMap { prevMessage ->
                    sequenceOf(
                        ClaudeCompletionStandardMessage("user", prevMessage.prompt),
                        ClaudeCompletionStandardMessage("assistant", prevMessage.response)
                    )
                }

            when {
                params.imageDetails != null -> {
                    messages.add(
                        ClaudeCompletionDetailedMessage(
                            "user",
                            listOf(
                                ClaudeMessageImageContent(
                                    ClaudeBase64Source(
                                        params.imageDetails!!.mediaType,
                                        params.imageDetails!!.data
                                    )
                                ),
                                ClaudeMessageTextContent(params.message.prompt)
                            )
                        )
                    )
                }

                else -> {
                    messages.add(
                        ClaudeCompletionStandardMessage(
                            "user", getPromptWithFilesContext(params)
                        )
                    )
                }
            }
        }
    }

    override fun createBasicCompletionRequest(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        stream: Boolean
    ): CompletionRequest {
        return ClaudeCompletionRequest().apply {
            system = systemPrompt
            isStream = stream
            model = service<AnthropicSettings>().state.model
            messages =
                listOf<ClaudeCompletionMessage>(ClaudeCompletionStandardMessage("user", userPrompt))
            this.maxTokens = maxTokens
        }
    }
}
