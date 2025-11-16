package ee.carlrobert.codegpt.conversations

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ee.carlrobert.codegpt.conversations.message.Message
import org.assertj.core.api.Assertions.assertThat

class ConversationsStateTest : BasePlatformTestCase() {

  fun testStartNewDefaultConversation() {
    val conversation = ConversationService.getInstance(project).startConversation()

    assertThat(conversation).isEqualTo(ConversationService.getInstance(project).currentConversation)
  }

  fun testSaveConversation() {
    val service = ConversationService.getInstance(project)
    val conversation = service.createConversation()
    service.addConversation(conversation)
    val message = Message("TEST_PROMPT")
    message.response = "TEST_RESPONSE"
    conversation.addMessage(message)

    service.saveConversation(conversation)

    val currentConversation = ConversationService.getInstance(project).currentConversation
    assertThat(currentConversation).isNotNull()
    assertThat(currentConversation!!.messages)
      .flatExtracting("prompt", "response")
      .containsExactly("TEST_PROMPT", "TEST_RESPONSE")
  }

  fun testGetPreviousConversation() {
    val service = ConversationService.getInstance(project)
    val firstConversation = service.startConversation()
    service.startConversation()

    val previousConversation = service.previousConversation

    assertThat(previousConversation.isPresent).isTrue()
    assertThat(previousConversation.get()).isEqualTo(firstConversation)
  }

  fun testGetNextConversation() {
    val service = ConversationService.getInstance(project)
    val firstConversation = service.startConversation()
    val secondConversation = service.startConversation()
    ConversationsState.getInstance(project).setCurrentConversation(firstConversation)

    val nextConversation = service.nextConversation

    assertThat(nextConversation.isPresent).isTrue()
    assertThat(nextConversation.get()).isEqualTo(secondConversation)
  }

  fun testDeleteSelectedConversation() {
    val service = ConversationService.getInstance(project)
    val firstConversation = service.startConversation()
    service.startConversation()

    service.deleteSelectedConversation()

    assertThat(ConversationService.getInstance(project).currentConversation).isEqualTo(firstConversation)
    assertThat(service.sortedConversations.size).isEqualTo(1)
    assertThat(service.sortedConversations)
      .extracting("id")
      .containsExactly(firstConversation.id)
  }

  fun testClearAllConversations() {
    val service = ConversationService.getInstance(project)
    service.startConversation()
    service.startConversation()

    service.clearAll()

    assertThat(ConversationService.getInstance(project).currentConversation).isNull()
    assertThat(service.sortedConversations.size).isEqualTo(0)
  }
}
