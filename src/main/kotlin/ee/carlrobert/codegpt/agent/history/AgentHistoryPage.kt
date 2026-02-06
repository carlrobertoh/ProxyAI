package ee.carlrobert.codegpt.agent.history

data class AgentHistoryPage(
    val items: List<AgentHistoryThreadSummary>,
    val hasMore: Boolean,
    val total: Int
)
