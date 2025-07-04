package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.action.HistoryActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.group.HistoryGroupItem
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest

class HistorySearchIntegrationTest : IntegrationTest() {

    private lateinit var conversationService: ConversationService
    private lateinit var searchManager: SearchManager
    private lateinit var historyGroupItem: HistoryGroupItem

    public override fun setUp() {
        super.setUp()
        conversationService = service<ConversationService>()
        ConversationsState.getInstance().conversations.clear()
        
        val tagManager = TagManager(testRootDisposable)
        searchManager = SearchManager(project, tagManager)
        historyGroupItem = HistoryGroupItem()
    }

    fun `test should include history group in search manager default groups`() {
        val defaultGroups = searchManager.getDefaultGroups()

        assertThat(defaultGroups).anyMatch { it is HistoryGroupItem }
    }

    fun `test should include history results in global search`() {
        createTestConversations()

        val results = runBlocking { searchManager.performGlobalSearch("java") }

        val historyResults = results.filterIsInstance<HistoryActionItem>()
        assertThat(historyResults).hasSizeGreaterThan(0)
        assertThat(historyResults.any { it.displayName.contains("Java", ignoreCase = true) }).isTrue()
    }

    fun `test should filter conversations by search terms`() {
        ConversationsState.getInstance().conversations.clear()
        createTestConversations()
        val testCases = mapOf(
            "java" to 3,
            "python" to 1,
            "javascript" to 2,
            "programming" to 1,
            "database" to 1,
            "nonexistent" to 0
        )

        testCases.forEach { (searchTerm, expectedCount) ->
            val results = runBlocking { historyGroupItem.getLookupItems(searchTerm) }

            assertThat(results)
                .withFailMessage("Search term '$searchTerm' should return $expectedCount results")
                .hasSize(expectedCount)
        }
    }

    fun `test should return all matching conversations for search term`() {
        val conversations = listOf(
            "Java programming basics" to "Learn Java fundamentals",
            "Advanced Java concepts" to "Deep dive into Java",
            "JavaScript vs Java comparison" to "Compare languages",
            "Python programming" to "Learn Python basics"
        )
        conversations.forEach { (prompt, response) ->
            val conversation = conversationService.createConversation()
            conversation.addMessage(Message(prompt).apply { this.response = response })
            conversationService.addConversation(conversation)
        }

        val javaResults = runBlocking { historyGroupItem.getLookupItems("java") }

        assertThat(javaResults).hasSize(3)
        val displayNames = javaResults.map { (it as HistoryActionItem).displayName }
        displayNames.forEach { name ->
            assertThat(name.lowercase()).contains("java")
        }
    }

    fun `test should handle special characters in search`() {
        val conversation = conversationService.createConversation()
        conversation.addMessage(Message("What is C++ programming?").apply { 
            response = "C++ is a powerful programming language" 
        })
        conversationService.addConversation(conversation)

        val results = runBlocking { historyGroupItem.getLookupItems("c++") }

        assertThat(results).hasSize(1)
        assertThat((results[0] as HistoryActionItem).displayName).contains("C++")
    }

    fun `test should perform search efficiently with many conversations`() {
        for (i in 1..100) {
            val conversation = conversationService.createConversation()
            val topic = when (i % 5) {
                0 -> "Java"
                1 -> "Python" 
                2 -> "JavaScript"
                3 -> "Database"
                else -> "General"
            }
            conversation.addMessage(Message("Question about $topic #$i").apply { 
                response = "Answer about $topic #$i" 
            })
            conversationService.addConversation(conversation)
        }

        val startTime = System.currentTimeMillis()
        val results = runBlocking { historyGroupItem.getLookupItems("java") }
        val endTime = System.currentTimeMillis()

        assertThat(results).hasSize(40)
        assertThat(endTime - startTime).isLessThan(1000)
        results.forEach { result ->
            val displayName = (result as HistoryActionItem).displayName
            assertThat(displayName.lowercase()).contains("java")
        }
    }

    fun `test should handle empty and whitespace queries`() {
        createTestConversations()

        val emptyResults = runBlocking { historyGroupItem.getLookupItems("") }
        val whitespaceResults = runBlocking { historyGroupItem.getLookupItems("   ") }

        assertThat(emptyResults).hasSizeLessThanOrEqualTo(10)
        assertThat(whitespaceResults).hasSizeLessThanOrEqualTo(emptyResults.size)
    }

    fun `test should search in conversation titles`() {
        val conversation1 = conversationService.createConversation()
        conversation1.addMessage(Message("How to use Docker containers?").apply { 
            response = "Docker containers are lightweight virtualization" 
        })
        conversationService.addConversation(conversation1)
        val conversation2 = conversationService.createConversation()
        conversation2.addMessage(Message("What is virtualization?").apply { 
            response = "Virtualization allows running multiple Docker instances" 
        })
        conversationService.addConversation(conversation2)

        val dockerResults = runBlocking { historyGroupItem.getLookupItems("docker") }
        val virtualizationResults = runBlocking { historyGroupItem.getLookupItems("virtualization") }

        assertThat(dockerResults).hasSize(1)
        assertThat((dockerResults[0] as HistoryActionItem).displayName).contains("Docker")
        assertThat(virtualizationResults).hasSize(1)
        assertThat((virtualizationResults[0] as HistoryActionItem).displayName).contains("virtualization")
    }

    fun `test should match history aliases in search manager`() {
        val historyAliases = listOf("history", "hist", "h")
        
        historyAliases.forEach { alias ->
            val matches = searchManager.matchesAnyDefaultGroup(alias)
            assertThat(matches)
                .withFailMessage("Alias '$alias' should match default group names")
                .isTrue()
        }
    }

    fun `test should have correct properties for history group item`() {
        assertThat(historyGroupItem.enabled).isTrue()
        assertThat(historyGroupItem.displayName).isEqualTo("History")
        assertThat(historyGroupItem.icon).isNotNull()
    }

    private fun createTestConversations() {
        val testData = listOf(
            "How to write Java code?" to "Use Java syntax and compile with javac",
            "Python vs JavaScript comparison" to "Python is interpreted, JavaScript runs in browsers", 
            "What is database normalization?" to "Database normalization reduces redundancy",
            "Best practices for programming" to "Write clean, readable, and testable code",
            "JavaScript async/await tutorial" to "Use async/await for asynchronous programming"
        )

        testData.forEach { (prompt, response) ->
            val conversation = conversationService.createConversation()
            conversation.addMessage(Message(prompt).apply { this.response = response })
            conversationService.addConversation(conversation)
        }
    }
}