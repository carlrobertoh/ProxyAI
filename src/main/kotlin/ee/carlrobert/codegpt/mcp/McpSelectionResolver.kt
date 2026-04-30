package ee.carlrobert.codegpt.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.settings.mcp.McpServerDetailsState
import ee.carlrobert.codegpt.settings.mcp.McpSettings
import ee.carlrobert.codegpt.ui.textarea.header.tag.McpTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails
import java.time.Instant
import java.util.*

@Service(Service.Level.APP)
class McpSelectionResolver {

    companion object {
        private val logger = thisLogger()
    }

    private val sessionManager = service<McpSessionManager>()
    private val tagStatusUpdater = service<McpTagStatusUpdater>()
    private val mcpSettings = service<McpSettings>()

    fun selectedServerIds(tags: Collection<TagDetails>): Set<String> {
        return tags
            .filterIsInstance<McpTagDetails>()
            .filter { it.selected }
            .map { it.serverId }
            .toSet()
    }

    fun toPendingTag(serverDetails: McpServerDetailsState): McpTagDetails {
        val serverId = serverDetails.id.toString()
        val serverName = serverDetails.name ?: "Unknown Server"
        return McpTagDetails(
            serverId = serverId,
            serverName = serverName,
            connectionStatus = ConnectionStatus.CONNECTING,
            serverCommand = serverDetails.command
        )
    }

    fun ensureConnected(
        conversationId: UUID,
        selectedTags: Collection<TagDetails>
    ): McpResolutionResult {
        val selectedMcpTags = selectedTags
            .filterIsInstance<McpTagDetails>()
            .filter { it.selected }

        if (selectedMcpTags.isEmpty()) {
            return McpResolutionResult.EMPTY
        }

        val attachmentsByServerId = sessionManager.getSessionAttachments(conversationId)
            .associateBy { it.serverId }
            .toMutableMap()

        val connectedServerIds = mutableListOf<String>()
        val failedServerIds = linkedMapOf<String, String>()
        val resolvedAttachments = mutableListOf<McpSessionAttachment>()

        selectedMcpTags.forEach { tag ->
            updateTag(
                conversationId,
                tag.serverId,
                tag.copy(connectionStatus = ConnectionStatus.CONNECTING)
            )

            val resolvedAttachment = attachmentsByServerId[tag.serverId]
                ?.takeIf { it.connectionStatus == ConnectionStatus.CONNECTED }
                ?: attachServer(conversationId, tag.serverId)
                    ?.also { attachmentsByServerId[tag.serverId] = it }

            when {
                resolvedAttachment == null -> {
                    val errorMessage = "Failed to attach MCP server"
                    failedServerIds[tag.serverId] = errorMessage
                    updateTag(
                        conversationId,
                        tag.serverId,
                        createErrorTag(tag, errorMessage)
                    )
                }

                resolvedAttachment.connectionStatus == ConnectionStatus.CONNECTED -> {
                    connectedServerIds += resolvedAttachment.serverId
                    resolvedAttachments += resolvedAttachment
                    updateTag(
                        conversationId,
                        resolvedAttachment.serverId,
                        resolvedAttachment.toTagDetails()
                    )
                }

                else -> {
                    val errorMessage =
                        resolvedAttachment.lastError ?: "Failed to connect MCP server"
                    failedServerIds[tag.serverId] = errorMessage
                    updateTag(
                        conversationId,
                        tag.serverId,
                        createErrorTag(tag, errorMessage, resolvedAttachment)
                    )
                }
            }
        }

        return McpResolutionResult(
            attachments = resolvedAttachments,
            tools = resolvedAttachments.flatMap { it.availableTools },
            connectedServerIds = connectedServerIds,
            failedServerIds = failedServerIds
        )
    }

    private fun attachServer(conversationId: UUID, serverId: String): McpSessionAttachment? {
        return runCatching {
            sessionManager.attachServerToSession(conversationId, serverId).get()
        }.onFailure { error ->
            logger.warn("Failed to resolve MCP server '$serverId'", error)
        }.getOrNull()
    }

    private fun updateTag(conversationId: UUID, serverId: String, tagDetails: McpTagDetails) {
        tagStatusUpdater.updateTagStatus(conversationId, serverId, tagDetails)
    }

    private fun createErrorTag(
        sourceTag: McpTagDetails,
        errorMessage: String,
        attachment: McpSessionAttachment? = null
    ): McpTagDetails {
        return McpTagDetails(
            serverId = sourceTag.serverId,
            serverName = attachment?.serverName ?: sourceTag.serverName,
            connectionStatus = ConnectionStatus.ERROR,
            availableTools = emptyList(),
            availableResources = emptyList(),
            lastError = errorMessage,
            serverCommand = sourceTag.serverCommand ?: getServerCommand(sourceTag.serverId),
            connectionTime = null
        )
    }

    private fun McpSessionAttachment.toTagDetails(): McpTagDetails {
        return McpTagDetails(
            serverId = serverId,
            serverName = serverName,
            connectionStatus = connectionStatus,
            availableTools = availableTools,
            availableResources = availableResources,
            lastError = lastError,
            serverCommand = getServerCommand(serverId),
            connectionTime = Instant.ofEpochMilli(attachedAt)
        )
    }

    private fun getServerCommand(serverId: String): String? {
        return mcpSettings.state.servers.find { it.id.toString() == serverId }?.command
    }
}

data class McpResolutionResult(
    val attachments: List<McpSessionAttachment>,
    val tools: List<McpTool>,
    val connectedServerIds: List<String>,
    val failedServerIds: Map<String, String>
) {
    companion object {
        val EMPTY = McpResolutionResult(emptyList(), emptyList(), emptyList(), emptyMap())
    }
}
