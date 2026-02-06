package ee.carlrobert.codegpt.agent.history

import kotlinx.datetime.Instant

enum class ThreadStatus {
    COMPLETED,
    PARTIAL,
    UNKNOWN
}

data class AgentHistoryThreadSummary(
    val agentId: String,
    val latest: CheckpointRef,
    val latestCreatedAt: Instant,
    val status: ThreadStatus,
    val runCount: Int,
    val title: String,
    val preview: String,
    val searchText: String = ""
)
