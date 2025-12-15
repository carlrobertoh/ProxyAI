package ee.carlrobert.codegpt.conversations.message

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenUsage(
    val promptTokens: Long,
    val completionTokens: Long,
    val toolTokens: Long = 0,
    val subAgentTokens: Long = 0,
    val totalTokens: Long = promptTokens + completionTokens + toolTokens + subAgentTokens
)