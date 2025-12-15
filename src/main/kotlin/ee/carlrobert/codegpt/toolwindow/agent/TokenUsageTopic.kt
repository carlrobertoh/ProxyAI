package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.util.messages.Topic

data class TokenUsageEvent(
    val sessionId: String,
    val totalTokens: Long,
)

interface TokenUsageListener {
    fun onTokenUsageChanged(event: TokenUsageEvent)
    
    companion object {
        val TOKEN_USAGE_TOPIC = Topic("Token Usage Changes", TokenUsageListener::class.java)
    }
}