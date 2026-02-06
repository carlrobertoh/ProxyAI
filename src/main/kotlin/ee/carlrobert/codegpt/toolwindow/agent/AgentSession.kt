package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.settings.service.ServiceType

/**
 * Represents a single Agent session with its own conversation state and metadata.
 * Each tab in the Agent tool window corresponds to one AgentSession.
 */
data class AgentSession(
    val sessionId: String,
    val conversation: Conversation,
    var displayName: String = "",
    var serviceType: ServiceType? = null,
    var runtimeAgentId: String? = null,
    var resumeCheckpointRef: CheckpointRef? = null,
    val referencedFiles: List<VirtualFile> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    var lastActiveAt: Long = System.currentTimeMillis()
)
