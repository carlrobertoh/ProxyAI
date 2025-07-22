package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.toolwindow.chat.MessageBuilder
import ee.carlrobert.codegpt.ui.textarea.header.tag.HistoryTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.action.HistoryActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.group.HistoryGroupItem
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.time.LocalDateTime
import java.util.*

class HistoryTagIntegrationTest : IntegrationTest() {

    private lateinit var conversationService: ConversationService

    public override fun setUp() {
        super.setUp()
        conversationService = service<ConversationService>()
        ConversationsState.getInstance().conversations.clear()
    }

    fun testShouldDisplayCorrectNameForHistoryActionItem() {
        val conversation = conversationService.createConversation()
        val message = Message("What is the capital of France?")
        message.response = "The capital of France is Paris."
        conversation.addMessage(message)

        val historyActionItem = HistoryActionItem(conversation)

        assertThat(historyActionItem.displayName).isEqualTo("What is the capital of France?")
    }

    fun testShouldTruncateLongPromptForDisplayName() {
        val longPrompt = "This is a very long prompt that should be truncated when used as a conversation title because it exceeds the 60 character limit"
        val conversation = conversationService.createConversation()
        val message = Message(longPrompt)
        message.response = "This is a response."
        conversation.addMessage(message)

        val historyActionItem = HistoryActionItem(conversation)

        assertThat(historyActionItem.displayName).hasSize(60)
        assertThat(historyActionItem.displayName).startsWith("This is a very long prompt that should be truncated when")
    }

    fun testShouldFallbackToResponseWhenPromptIsNull() {
        val conversation = conversationService.createConversation()
        val message = Message("")
        message.prompt = null
        message.response = "This is only a response without a prompt."
        conversation.addMessage(message)

        val historyActionItem = HistoryActionItem(conversation)

        assertThat(historyActionItem.displayName).isEqualTo("This is only a response without a prompt.")
    }

    fun testShouldUseDefaultTitleWhenBothPromptAndResponseAreNull() {
        val conversation = conversationService.createConversation()
        val message = Message("")
        message.prompt = null
        message.response = null
        conversation.addMessage(message)

        val historyActionItem = HistoryActionItem(conversation)

        assertThat(historyActionItem.displayName).isEqualTo("Conversation")
    }

    fun testShouldCreateCorrectTagDetails() {
        val conversation = conversationService.createConversation()
        val message = Message("What is the capital of France?")
        message.response = "The capital of France is Paris."
        conversation.addMessage(message)
        val historyActionItem = HistoryActionItem(conversation)

        val expectedTag = HistoryTagDetails(conversation.id, historyActionItem.displayName)

        assertThat(expectedTag.conversationId).isEqualTo(conversation.id)
        assertThat(expectedTag.title).isEqualTo("What is the capital of France?")
        assertThat(historyActionItem.icon).isNotNull()
    }

    fun testShouldFilterConversationsBySearchText() {
        val historyGroupItem = HistoryGroupItem()
        val javaConversation = conversationService.createConversation()
        javaConversation.addMessage(Message("How to write Java code?").apply { response = "Use Java syntax" })
        conversationService.addConversation(javaConversation)
        val pythonConversation = conversationService.createConversation()
        pythonConversation.addMessage(Message("Python programming tutorial").apply { response = "Learn Python basics" })
        conversationService.addConversation(pythonConversation)

        val javaResults = runBlocking { historyGroupItem.getLookupItems("java") }
        val pythonResults = runBlocking { historyGroupItem.getLookupItems("python") }

        assertThat(javaResults).hasSize(1)
        assertThat((javaResults[0] as HistoryActionItem).displayName).contains("Java")
        assertThat(pythonResults).hasSize(1)
        assertThat((pythonResults[0] as HistoryActionItem).displayName).contains("Python")
    }

    fun testShouldHandleEmptyConversationsList() {
        val historyGroupItem = HistoryGroupItem()

        val results = runBlocking { historyGroupItem.getLookupItems("") }

        assertThat(results).isEmpty()
    }

    fun testShouldPerformCaseInsensitiveSearch() {
        val historyGroupItem = HistoryGroupItem()
        val conversation = conversationService.createConversation()
        conversation.addMessage(Message("JavaScript Tutorial").apply { response = "Learn JS" })
        conversationService.addConversation(conversation)

        val upperCaseResults = runBlocking { historyGroupItem.getLookupItems("JAVASCRIPT") }
        val lowerCaseResults = runBlocking { historyGroupItem.getLookupItems("javascript") }
        val mixedCaseResults = runBlocking { historyGroupItem.getLookupItems("JavaScript") }

        assertThat(upperCaseResults).hasSize(1)
        assertThat(lowerCaseResults).hasSize(1)
        assertThat(mixedCaseResults).hasSize(1)
    }

    fun testShouldIntegrateHistoryTagsInMessageBuilder() {
        val existingConversation = conversationService.createConversation()
        val existingMessage = Message("Previous question")
        existingMessage.response = "Previous answer"
        existingConversation.addMessage(existingMessage)
        val historyTag = HistoryTagDetails(
            conversationId = existingConversation.id,
            title = "Previous conversation"
        )

        val message = MessageBuilder(project, "New question referencing history")
            .withInlays(listOf(historyTag))
            .build()

        assertThat(message.prompt).isEqualTo("New question referencing history")
    }

    fun testShouldFormatConversationCorrectly() {
        val conversation = conversationService.createConversation()
        conversation.addMessage(Message("First question").apply { response = "First answer" })
        conversation.addMessage(Message("Second question").apply { response = "Second answer" })

        val formatted = ConversationTagProcessor.formatConversation(conversation)

        assertThat(formatted).contains("# History")
        assertThat(formatted).contains("## Conversation: First question")
        assertThat(formatted).contains("**User**: First question")
        assertThat(formatted).contains("**Assistant**: First answer")
        assertThat(formatted).contains("**User**: Second question")
        assertThat(formatted).contains("**Assistant**: Second answer")
    }

    fun testShouldFindConversationById() {
        val conversation = conversationService.createConversation()
        conversation.addMessage(Message("Test question").apply { response = "Test answer" })
        conversationService.addConversation(conversation)

        val foundConversation = ConversationTagProcessor.getConversation(conversation.id)

        assertThat(foundConversation).isNotNull
        assertThat(foundConversation!!.id).isEqualTo(conversation.id)
        assertThat(foundConversation.messages).hasSize(1)
        assertThat(foundConversation.messages[0].prompt).isEqualTo("Test question")
    }

    fun testShouldReturnNullForNonExistentConversation() {
        val nonExistentId = UUID.randomUUID()

        val foundConversation = ConversationTagProcessor.getConversation(nonExistentId)

        assertThat(foundConversation).isNull()
    }

    fun testShouldIncludeHistoryGroupInSearchManager() {
        val tagManager = TagManager(testRootDisposable)
        val searchManager = SearchManager(project, tagManager)

        val defaultGroups = searchManager.getDefaultGroups()

        assertThat(defaultGroups).anyMatch { it is HistoryGroupItem }
    }

    fun testShouldIncludeHistoryAliasesInConstants() {
        assertThat(PromptTextFieldConstants.DEFAULT_GROUP_NAMES)
            .contains("history", "hist", "h")
    }

    fun testShouldImplementEqualsAndHashcodeCorrectlyForHistoryTagDetails() {
        val conversationId = UUID.randomUUID()
        val tag1 = HistoryTagDetails(conversationId, "Test conversation")
        val tag2 = HistoryTagDetails(conversationId, "Test conversation")
        val tag3 = HistoryTagDetails(UUID.randomUUID(), "Different conversation")

        assertThat(tag1).isEqualTo(tag2)
        assertThat(tag1).isNotEqualTo(tag3)
        assertThat(tag1.hashCode()).isEqualTo(tag2.hashCode())
        assertThat(tag1.hashCode()).isNotEqualTo(tag3.hashCode())
    }

    fun testShouldSortConversationsByUpdatedDateDescending() {
        val historyGroupItem = HistoryGroupItem()
        val now = LocalDateTime.now()
        val oldConversation = conversationService.createConversation()
        oldConversation.updatedOn = now.minusDays(3)
        oldConversation.addMessage(Message("Old conversation").apply { response = "Old response" })
        conversationService.addConversation(oldConversation)
        val newestConversation = conversationService.createConversation()
        newestConversation.updatedOn = now
        newestConversation.addMessage(Message("Newest conversation").apply { response = "Newest response" })
        conversationService.addConversation(newestConversation)
        val middleConversation = conversationService.createConversation()
        middleConversation.updatedOn = now.minusDays(1)
        middleConversation.addMessage(Message("Middle conversation").apply { response = "Middle response" })
        conversationService.addConversation(middleConversation)

        val results = runBlocking { historyGroupItem.getLookupItems("") }

        assertThat(results).hasSize(3)
        val displayNames = results.map { (it as HistoryActionItem).displayName }
        assertThat(displayNames[0]).isEqualTo("Newest conversation")
        assertThat(displayNames[1]).isEqualTo("Middle conversation")
        assertThat(displayNames[2]).isEqualTo("Old conversation")
    }

}