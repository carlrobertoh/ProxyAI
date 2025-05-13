package ee.carlrobert.codegpt.completions

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.factory.OpenAIRequestFactory
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple
import testsupport.IntegrationTest

class CompletionRequestProviderTest : IntegrationTest() {

    fun testChatCompletionRequestWithSystemPromptOverride() {
        useOpenAIService(OpenAIChatCompletionModel.GPT_4_O.code)
        service<PromptsSettings>().state.personas.selectedPersona.instructions = "TEST_SYSTEM_PROMPT"
        val conversation = ConversationService.getInstance().startConversation()
        val firstMessage = createDummyMessage(500)
        val secondMessage = createDummyMessage(250)
        conversation.addMessage(firstMessage)
        conversation.addMessage(secondMessage)
        val callParameters = ChatCompletionParameters
            .builder(conversation, Message("TEST_CHAT_COMPLETION_PROMPT"))
            .build()

        val request = OpenAIRequestFactory().createChatRequest(callParameters)

        assertThat(request.messages)
            .extracting("role", "content")
            .containsExactly(
                Tuple.tuple("system", "TEST_SYSTEM_PROMPT"),
                Tuple.tuple("user", "TEST_PROMPT"),
                Tuple.tuple("assistant", firstMessage.response),
                Tuple.tuple("user", "TEST_PROMPT"),
                Tuple.tuple("assistant", secondMessage.response),
                Tuple.tuple("user", "TEST_CHAT_COMPLETION_PROMPT")
            )
    }

    fun testChatCompletionRequestRetry() {
        useOpenAIService(OpenAIChatCompletionModel.GPT_4_O.code)
        service<PromptsSettings>().state.personas.selectedPersona.instructions = "TEST_SYSTEM_PROMPT"
        val conversation = ConversationService.getInstance().startConversation()
        val firstMessage = createDummyMessage("FIRST_TEST_PROMPT", 500)
        val secondMessage = createDummyMessage("SECOND_TEST_PROMPT", 250)
        conversation.addMessage(firstMessage)
        conversation.addMessage(secondMessage)
        val callParameters = ChatCompletionParameters.builder(conversation, secondMessage)
            .retry(true)
            .build()

        val request = OpenAIRequestFactory().createChatRequest(callParameters)

        assertThat(request.messages)
            .extracting("role", "content")
            .containsExactly(
                Tuple.tuple("system", "TEST_SYSTEM_PROMPT"),
                Tuple.tuple("user", "FIRST_TEST_PROMPT"),
                Tuple.tuple("assistant", firstMessage.response),
                Tuple.tuple("user", "SECOND_TEST_PROMPT")
            )
    }

    fun testTotalUsageExceededException() {
        useOpenAIService(OpenAIChatCompletionModel.GPT_3_5.code)
        val conversation = ConversationService.getInstance().startConversation()
        conversation.addMessage(createDummyMessage(1500))
        conversation.addMessage(createDummyMessage(1500))
        conversation.addMessage(createDummyMessage(1500))

        assertThrows(TotalUsageExceededException::class.java) {
            OpenAIRequestFactory().createChatRequest(
                ChatCompletionParameters
                    .builder(conversation, createDummyMessage(100))
                    .build()
            )
        }
    }

    private fun createDummyMessage(tokenSize: Int): Message {
        return createDummyMessage("TEST_PROMPT", tokenSize)
    }

    private fun createDummyMessage(prompt: String, tokenSize: Int): Message {
        val message = Message(prompt)
        // 'zz' = 1 token, prompt = 6 tokens, 7 tokens per message (GPT-3),
        message.response = "zz".repeat((tokenSize) - 6 - 7)
        return message
    }
}
