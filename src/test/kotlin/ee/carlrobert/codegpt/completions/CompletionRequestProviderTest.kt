package ee.carlrobert.codegpt.completions

import ai.koog.prompt.message.Message as KoogMessage
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.factory.OpenAIRequestFactory
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.settings.prompts.PersonaPromptDetailsState
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import testsupport.IntegrationTest

class CompletionRequestProviderTest : IntegrationTest() {

    fun testChatCompletionRequestWithSystemPromptOverride() {
        useOpenAIService("gpt-4o")
        val customPersona = PersonaPromptDetailsState().apply {
            id = 999L
            name = "Test Persona"
            instructions = "TEST_SYSTEM_PROMPT"
        }
        service<PromptsSettings>().state.personas.selectedPersona = customPersona
        val conversation = ConversationService.getInstance().startConversation(project)
        val firstMessage = createDummyMessage(500)
        val secondMessage = createDummyMessage(250)
        conversation.addMessage(firstMessage)
        conversation.addMessage(secondMessage)
        val callParameters = ChatCompletionParameters
            .builder(project, conversation, Message("TEST_CHAT_COMPLETION_PROMPT"))
            .build()

        val prompt = OpenAIRequestFactory().createChatCompletionPrompt(callParameters)
        val normalized = normalize(prompt.messages)

        assertThat(normalized[0]["content"]).contains("TEST_SYSTEM_PROMPT")
        assertThat(normalized.drop(1))
            .extracting("role", "content")
            .containsExactly(
                tuple("user", "TEST_PROMPT"),
                tuple("assistant", firstMessage.response),
                tuple("user", "TEST_PROMPT"),
                tuple("assistant", secondMessage.response),
                tuple("user", "TEST_CHAT_COMPLETION_PROMPT")
            )
    }

    fun testChatCompletionRequestRetry() {
        useOpenAIService("gpt-4o")
        val customPersona = PersonaPromptDetailsState().apply {
            id = 999L
            name = "Test Persona"
            instructions = "TEST_SYSTEM_PROMPT"
        }
        service<PromptsSettings>().state.personas.selectedPersona = customPersona
        val conversation = ConversationService.getInstance().startConversation(project)
        val firstMessage = createDummyMessage("FIRST_TEST_PROMPT", 500)
        val secondMessage = createDummyMessage("SECOND_TEST_PROMPT", 250)
        conversation.addMessage(firstMessage)
        conversation.addMessage(secondMessage)
        val callParameters = ChatCompletionParameters.builder(project, conversation, secondMessage)
            .retry(true)
            .build()

        val prompt = OpenAIRequestFactory().createChatCompletionPrompt(callParameters)
        val normalized = normalize(prompt.messages)

        assertThat(normalized[0]["content"]).contains("TEST_SYSTEM_PROMPT")
        assertThat(normalized.drop(1))
            .extracting("role", "content")
            .containsExactly(
                tuple("user", "FIRST_TEST_PROMPT"),
                tuple("assistant", firstMessage.response),
                tuple("user", "SECOND_TEST_PROMPT")
            )
    }

    private fun createDummyMessage(tokenSize: Int): Message {
        return createDummyMessage("TEST_PROMPT", tokenSize)
    }

    private fun createDummyMessage(prompt: String, tokenSize: Int): Message {
        val message = Message(prompt)
        message.response = "zz".repeat((tokenSize) - 6 - 7)
        return message
    }

    private fun normalize(messages: List<KoogMessage>): List<Map<String, String>> {
        return messages.mapNotNull { msg ->
            when (msg) {
                is KoogMessage.System -> mapOf("role" to "system", "content" to msg.content)
                is KoogMessage.User -> mapOf("role" to "user", "content" to msg.content)
                is KoogMessage.Assistant -> mapOf("role" to "assistant", "content" to msg.content)
                else -> null
            }
        }
    }
}
