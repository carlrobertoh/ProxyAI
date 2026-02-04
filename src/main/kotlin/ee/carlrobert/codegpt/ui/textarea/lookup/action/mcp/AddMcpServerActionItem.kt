package ee.carlrobert.codegpt.ui.textarea.lookup.action.mcp

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.mcp.McpStatusBridge
import ee.carlrobert.codegpt.settings.mcp.McpServerDetailsState
import ee.carlrobert.codegpt.settings.mcp.McpSettings
import ee.carlrobert.codegpt.settings.mcp.form.McpJsonImportDialog
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem
import java.util.*

class AddMcpServerActionItem : AbstractLookupActionItem() {

    override val displayName = CodeGPTBundle.get("suggestionActionItem.addMcpServer.displayName")
    override val icon = AllIcons.General.Add

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        val dialog = McpJsonImportDialog()
        if (dialog.showAndGet()) {
            val importedServers = dialog.importedServers
            if (importedServers.isNotEmpty()) {
                val mcpSettings = service<McpSettings>()
                val existingServerIds = mcpSettings.state.servers.map { it.id }.toSet()

                val newServers = mutableListOf<McpServerDetailsState>()
                importedServers.forEach { serverDetails ->
                    var newId = serverDetails.id
                    while (existingServerIds.contains(newId) || newServers.any { it.id == newId }) {
                        newId = System.currentTimeMillis() + (0..1000).random()
                    }

                    val newServerState = McpServerDetailsState().apply {
                        id = newId
                        name = serverDetails.name
                        command = serverDetails.command
                        arguments = serverDetails.arguments.toMutableList()
                        environmentVariables = serverDetails.environmentVariables.toMutableMap()
                    }

                    mcpSettings.state.servers.add(newServerState)
                    newServers.add(newServerState)
                }

                val conversationId = userInputPanel.getConversationId()
                    ?: ConversationsState.getCurrentConversation()?.id
                if (conversationId != null && newServers.isNotEmpty()) {
                    val firstServer = newServers.first()
                    autoAttachServer(firstServer, conversationId, userInputPanel)
                }
            }
        }
    }

    private fun autoAttachServer(
        serverDetails: McpServerDetailsState,
        conversationId: UUID,
        userInputPanel: UserInputPanel
    ) {
        val statusBridge = service<McpStatusBridge>()
        statusBridge.attachServerAndUpdateUi(
            conversationId,
            serverDetails.id.toString(),
            serverDetails.name ?: "Unknown Server",
            userInputPanel
        )
    }
}
