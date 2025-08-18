package ee.carlrobert.codegpt.codecompletions

import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionStandardMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChatBasedFIMTest {

    @Test
    fun `test chat completion template is available`() {
        val templates = InfillPromptTemplate.values()
        val chatTemplate = templates.find { it == InfillPromptTemplate.CHAT_COMPLETION }
        
        assertNotNull(chatTemplate)
        assertEquals("Chat-based FIM", chatTemplate?.label)
        assertEquals(listOf("\n\n", "```"), chatTemplate?.stopTokens)
    }

    @Test
    fun `test chat completion template builds placeholder`() {
        val template = InfillPromptTemplate.CHAT_COMPLETION
        val infillRequest = InfillRequest.Builder("function test() {", "}", 0).build()
        
        val result = template.buildPrompt(infillRequest)
        assertEquals("CHAT_FIM_PLACEHOLDER", result)
    }

    @Test
    fun `test chat based FIM request creation`() {
        val infillRequest = InfillRequest.Builder(
            "function calculateSum(a, b) {",
            "return result;\n}",
            0
        ).build()
        
        val chatRequest = CodeCompletionRequestFactory.buildChatBasedFIMRequest(infillRequest)
        
        assertNotNull(chatRequest)
        assertEquals(2, chatRequest.messages.size)
        
        val systemMessage = chatRequest.messages[0]
        assertEquals("system", systemMessage.role)
        assertTrue(systemMessage.content.contains("expert coding assistant"))
        
        val userMessage = chatRequest.messages[1]
        assertEquals("user", userMessage.role)
        assertTrue(userMessage.content.contains("PREFIX:"))
        assertTrue(userMessage.content.contains("SUFFIX:"))
        assertTrue(userMessage.content.contains("function calculateSum(a, b) {"))
        assertTrue(userMessage.content.contains("return result;"))
        
        assertTrue(chatRequest.stream)
        assertEquals(128, chatRequest.maxTokens)
        assertEquals(0.0, chatRequest.temperature)
    }

    @Test
    fun `test custom request throws exception for chat completion template`() {
        val infillRequest = InfillRequest.Builder("test", "test", 0).build()
        
        assertThrows(IllegalArgumentException::class.java) {
            CodeCompletionRequestFactory.buildCustomRequest(
                infillRequest,
                "http://test.com",
                emptyMap(),
                emptyMap(),
                InfillPromptTemplate.CHAT_COMPLETION,
                null
            )
        }
    }

    @Test
    fun `test chat based FIM HTTP request creation`() {
        val infillRequest = InfillRequest.Builder("test prefix", "test suffix", 0).build()
        val headers = mapOf("Authorization" to "Bearer \$CUSTOM_SERVICE_API_KEY")
        val body = mapOf<String, Any>(
            "model" to "gpt-4.1",
            "temperature" to 0.2,
            "max_tokens" to 24,
            "stream" to false
        )
        
        val httpRequest = CodeCompletionRequestFactory.buildChatBasedFIMHttpRequest(
            infillRequest,
            "https://api.openai.com/v1/chat/completions",
            headers,
            body,
            "test-api-key"
        )
        
        assertNotNull(httpRequest)
        assertEquals("https://api.openai.com/v1/chat/completions", httpRequest.url.toString())
        assertEquals("Bearer test-api-key", httpRequest.header("Authorization"))
        assertEquals("application/json", httpRequest.body?.contentType()?.toString())
    }
}
