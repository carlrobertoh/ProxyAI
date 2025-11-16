package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.ui.textarea.lookup.action.HistoryActionItem
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.util.*

class ConversationTagProcessorTest : IntegrationTest() {

    private lateinit var conversationService: ConversationService

    public override fun setUp() {
        super.setUp()
        conversationService = project.service<ConversationService>()
        ConversationsState.getInstance(project).conversations.clear()
    }

    fun `test should format conversation with single message`() {
        val conversation = conversationService.createConversation()
        conversation.addMessage(Message("How do I create a REST API?").apply {
            response = "You can create a REST API using frameworks like Spring Boot or Express.js."
        })

        val formatted = ConversationTagProcessor.formatConversation(conversation)

        assertThat(formatted).contains("# History")
        assertThat(formatted).contains("## Conversation: How do I create a REST API?")
        assertThat(formatted).contains("**User**: How do I create a REST API?")
        assertThat(formatted).contains("**Assistant**: You can create a REST API using frameworks like Spring Boot or Express.js.")
    }

    fun `test should format conversation with multiple messages`() {
        val conversation = conversationService.createConversation()
        conversation.addMessage(Message("What is Docker?").apply {
            response = "Docker is a containerization platform."
        })
        conversation.addMessage(Message("How do I install Docker?").apply {
            response = "You can install Docker from docker.com."
        })
        conversation.addMessage(Message("What are Docker containers?").apply {
            response = "Containers are lightweight, portable environments."
        })

        val formatted = ConversationTagProcessor.formatConversation(conversation)

        assertThat(formatted).contains("# History")
        assertThat(formatted).contains("## Conversation: What is Docker?")
        assertThat(formatted).contains("**User**: What is Docker?")
        assertThat(formatted).contains("**Assistant**: Docker is a containerization platform.")
        assertThat(formatted).contains("**User**: How do I install Docker?")
        assertThat(formatted).contains("**Assistant**: You can install Docker from docker.com.")
        assertThat(formatted).contains("**User**: What are Docker containers?")
        assertThat(formatted).contains("**Assistant**: Containers are lightweight, portable environments.")
    }

    fun `test should handle null prompt in formatting`() {
        val conversation = conversationService.createConversation()
        conversation.addMessage(Message("").apply {
            prompt = null
            response = "This is a response without a prompt."
        })

        val formatted = ConversationTagProcessor.formatConversation(conversation)

        assertThat(formatted).contains("# History")
        assertThat(formatted).contains("**User**: null")
        assertThat(formatted).contains("**Assistant**: This is a response without a prompt.")
    }

    fun `test should handle null response in formatting`() {
        val conversation = conversationService.createConversation()
        conversation.addMessage(Message("What is AI?").apply {
            response = null
        })

        val formatted = ConversationTagProcessor.formatConversation(conversation)

        assertThat(formatted).contains("# History")
        assertThat(formatted).contains("**User**: What is AI?")
        assertThat(formatted).contains("**Assistant**: null")
    }

    fun `test should use first message as conversation title`() {
        val conversation = conversationService.createConversation()
        conversation.addMessage(Message("First message").apply {
            response = "First response"
        })
        conversation.addMessage(Message("Second message").apply {
            response = "Second response"
        })

        val formatted = ConversationTagProcessor.formatConversation(conversation)

        assertThat(formatted).contains("## Conversation: First message")
        assertThat(formatted).doesNotContain("## Conversation: Second message")
    }

    fun `test should find current conversation by id`() {
        val conversation = conversationService.startConversation()
        conversation.addMessage(Message("Current conversation test").apply {
            response = "This is the current conversation"
        })

        val foundConversation = ConversationTagProcessor.getConversation(project, conversation.id)

        assertThat(foundConversation).isNotNull
        assertThat(foundConversation!!.id).isEqualTo(conversation.id)
        assertThat(foundConversation.messages).hasSize(1)
        assertThat(foundConversation.messages[0].prompt).isEqualTo("Current conversation test")
    }

    fun `test should find stored conversation by id`() {
        val conversation = conversationService.createConversation()
        conversation.addMessage(Message("Stored conversation test").apply {
            response = "This is a stored conversation"
        })
        conversationService.addConversation(conversation)

        val foundConversation = ConversationTagProcessor.getConversation(project, conversation.id)

        assertThat(foundConversation).isNotNull
        assertThat(foundConversation!!.id).isEqualTo(conversation.id)
        assertThat(foundConversation.messages).hasSize(1)
        assertThat(foundConversation.messages[0].prompt).isEqualTo("Stored conversation test")
    }

    fun `test should prefer current conversation over stored when same id`() {
        val storedConversation = conversationService.createConversation()
        storedConversation.addMessage(Message("Stored version").apply {
            response = "This is stored"
        })
        conversationService.addConversation(storedConversation)
        val currentConversation = conversationService.startConversation()
        currentConversation.id = storedConversation.id
        currentConversation.addMessage(Message("Current version").apply {
            response = "This is current"
        })

        val foundConversation = ConversationTagProcessor.getConversation(project, storedConversation.id)

        assertThat(foundConversation).isNotNull
        assertThat(foundConversation!!.messages[0].prompt).isEqualTo("Current version")
    }

    fun `test should return null for non-existent conversation`() {
        val nonExistentId = UUID.randomUUID()

        val foundConversation = ConversationTagProcessor.getConversation(project, nonExistentId)

        assertThat(foundConversation).isNull()
    }

    fun `test should integrate with history action item`() {
        val conversation = conversationService.createConversation()
        conversation.addMessage(Message("Integration test prompt").apply {
            response = "Integration test response"
        })
        conversationService.addConversation(conversation)
        val historyActionItem = HistoryActionItem(conversation)
        val foundConversation = ConversationTagProcessor.getConversation(project, conversation.id)

        val conversationTitle = historyActionItem.displayName
        val formatted = ConversationTagProcessor.formatConversation(foundConversation!!)

        assertThat(conversationTitle).isEqualTo("Integration test prompt")
        assertThat(formatted).contains("## Conversation: Integration test prompt")
        assertThat(formatted).contains("**User**: Integration test prompt")
        assertThat(formatted).contains("**Assistant**: Integration test response")
    }

    fun `test should handle empty message list`() {
        val conversation = conversationService.createConversation()

        val formatted = ConversationTagProcessor.formatConversation(conversation)

        assertThat(formatted).contains("# History")
        assertThat(formatted).contains("## Conversation: Conversation")
    }

    fun `test should handle long messages and truncate title`() {
        val conversation = conversationService.createConversation()
        val longPrompt =
            "This is a very long prompt that contains a lot of text to test how the conversation formatter handles lengthy content. " +
                    "It should include all the text without truncating it, unlike the title which gets truncated to 60 characters."
        val longResponse =
            "This is an equally long response that provides detailed information about the question asked. " +
                    "The response should also be included in full without any truncation when formatting the conversation history."
        conversation.addMessage(Message(longPrompt).apply {
            response = longResponse
        })

        val formatted = ConversationTagProcessor.formatConversation(conversation)

        assertThat(formatted).contains("# History")
        assertThat(formatted).contains("**User**: $longPrompt")
        assertThat(formatted).contains("**Assistant**: $longResponse")
        val titleInFormatted = formatted.substringAfter("## Conversation: ").substringBefore("\n")
        assertThat(titleInFormatted).hasSize(60)
    }
}
