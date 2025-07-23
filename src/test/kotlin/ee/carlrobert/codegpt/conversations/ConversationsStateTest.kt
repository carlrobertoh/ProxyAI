package ee.carlrobert.codegpt.conversations

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel
import org.assertj.core.api.Assertions.assertThat

class ConversationsStateTest : BasePlatformTestCase() {

  fun testStartNewDefaultConversation() {
    val conversation = ConversationService.getInstance().startConversation(project)

    assertThat(conversation).isEqualTo(ConversationsState.getCurrentConversation())
  }

  fun testSaveConversation() {
    val service = ConversationService.getInstance()
    val conversation = service.createConversation()
    service.addConversation(conversation)
    val message = Message("TEST_PROMPT")
    message.response = "TEST_RESPONSE"
    conversation.addMessage(message)

    service.saveConversation(conversation)

    val currentConversation = ConversationsState.getCurrentConversation()
    assertThat(currentConversation).isNotNull()
    assertThat(currentConversation!!.messages)
      .flatExtracting("prompt", "response")
      .containsExactly("TEST_PROMPT", "TEST_RESPONSE")
  }

  fun testGetPreviousConversation() {
    val service = ConversationService.getInstance()
    val firstConversation = service.startConversation(project)
    service.startConversation(project)

    val previousConversation = service.previousConversation

    assertThat(previousConversation.isPresent).isTrue()
    assertThat(previousConversation.get()).isEqualTo(firstConversation)
  }

  fun testGetNextConversation() {
    val service = ConversationService.getInstance()
    val firstConversation = service.startConversation(project)
    val secondConversation = service.startConversation(project)
    ConversationsState.getInstance().setCurrentConversation(firstConversation)

    val nextConversation = service.nextConversation

    assertThat(nextConversation.isPresent).isTrue()
    assertThat(nextConversation.get()).isEqualTo(secondConversation)
  }

  fun testDeleteSelectedConversation() {
    val service = ConversationService.getInstance()
    val firstConversation = service.startConversation(project)
    service.startConversation(project)

    service.deleteSelectedConversation()

    assertThat(ConversationsState.getCurrentConversation()).isEqualTo(firstConversation)
    assertThat(service.sortedConversations.size).isEqualTo(1)
    assertThat(service.sortedConversations)
      .extracting("id")
      .containsExactly(firstConversation.id)
  }

  fun testClearAllConversations() {
    val service = ConversationService.getInstance()
    service.startConversation(project)
    service.startConversation(project)

    service.clearAll()

    assertThat(ConversationsState.getCurrentConversation()).isNull()
    assertThat(service.sortedConversations.size).isEqualTo(0)
  }
}
