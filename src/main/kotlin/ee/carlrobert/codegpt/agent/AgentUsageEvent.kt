package ee.carlrobert.codegpt.agent

data class AgentUsageEvent(
    val usedTokens: Long,
    val sizeTokens: Long? = null,
    val costAmount: Double? = null,
    val costCurrency: String? = null
)
