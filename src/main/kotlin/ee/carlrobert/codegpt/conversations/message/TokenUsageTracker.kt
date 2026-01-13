package ee.carlrobert.codegpt.conversations.message

import java.util.concurrent.atomic.AtomicLong

class TokenUsageTracker {
    private val promptTokens = AtomicLong(0)
    private val completionTokens = AtomicLong(0)
    private val toolTokens = AtomicLong(0)
    private val subAgentTokens = AtomicLong(0)

    fun setPromptTokens(tokens: Long) {
        promptTokens.set(tokens.coerceAtLeast(0))
    }

    fun addPromptTokens(tokens: Long) {
        promptTokens.addAndGet(tokens)
    }

    fun addCompletionTokens(tokens: Long) {
        completionTokens.addAndGet(tokens)
    }

    fun addToolTokens(tokens: Long) {
        toolTokens.addAndGet(tokens)
    }

    fun addSubAgentTokens(tokens: Long) {
        subAgentTokens.addAndGet(tokens)
    }

    fun getPromptTokens(): Long = promptTokens.get()

    fun getTotalTokens(): Long =
        promptTokens.get() + completionTokens.get() + toolTokens.get() + subAgentTokens.get()

    fun updateTokenUsage(tokenUsage: TokenUsage?) {
        tokenUsage?.let { usage ->
            addPromptTokens(usage.promptTokens)
            addCompletionTokens(usage.completionTokens)
            addToolTokens(usage.toolTokens)
            addSubAgentTokens(usage.subAgentTokens)
        }
    }

    fun reset() {
        promptTokens.set(0)
        completionTokens.set(0)
        toolTokens.set(0)
        subAgentTokens.set(0)
    }

    fun isEmpty(): Boolean = getTotalTokens() == 0L
}
