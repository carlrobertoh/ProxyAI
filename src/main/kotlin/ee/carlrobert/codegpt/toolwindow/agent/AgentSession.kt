package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.settings.service.ServiceType

data class AcpConfigOptionChoice(
    val value: String,
    val name: String,
    val description: String? = null
)

data class AcpConfigOption(
    val id: String,
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val type: String? = null,
    val currentValue: String? = null,
    val options: List<AcpConfigOptionChoice> = emptyList()
)

/**
 * Represents a single Agent session with its own conversation state and metadata.
 * Each tab in the Agent tool window corresponds to one AgentSession.
 */
data class AgentSession(
    val sessionId: String,
    val conversation: Conversation,
    var displayName: String = "",
    var serviceType: ServiceType? = null,
    var externalAgentId: String? = null,
    var externalAgentSessionId: String? = null,
    var externalAgentConfigOptions: List<AcpConfigOption> = emptyList(),
    var externalAgentConfigLoading: Boolean = false,
    var runtimeAgentId: String? = null,
    var resumeCheckpointRef: CheckpointRef? = null,
    val referencedFiles: List<VirtualFile> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    var lastActiveAt: Long = System.currentTimeMillis()
) {
    var externalAgentErrorMessage: String? = null
}
