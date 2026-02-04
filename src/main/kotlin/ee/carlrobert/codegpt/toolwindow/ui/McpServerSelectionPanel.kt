package ee.carlrobert.codegpt.toolwindow.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.mcp.ConnectionStatus
import ee.carlrobert.codegpt.mcp.McpSessionManager
import ee.carlrobert.codegpt.mcp.McpStatusBridge
import ee.carlrobert.codegpt.settings.mcp.McpConfigurable
import ee.carlrobert.codegpt.settings.mcp.McpServerDetailsState
import ee.carlrobert.codegpt.settings.mcp.McpSettings
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class McpServerSelectionPanel(
    private val project: Project,
    private val userInputPanel: UserInputPanel
) : BorderLayoutPanel() {

    private val serverListModel = DefaultListModel<McpServerItem>()
    private val serverList = JBList(serverListModel)
    private val noServersLabel = JBLabel("No MCP servers configured", SwingConstants.CENTER)
    private val emptyPanel = BorderLayoutPanel()

    init {
        setupUI()
        setupServerList()
        refreshServerList()
    }

    private fun setupUI() {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        preferredSize = Dimension(280, 150)

        emptyPanel.add(noServersLabel, BorderLayout.CENTER)
        noServersLabel.foreground = UIUtil.getInactiveTextColor()

        val scrollPane = JBScrollPane(serverList).apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            border = JBUI.Borders.empty()
        }

        add(scrollPane, BorderLayout.CENTER)

        val toolbar = createToolbar()
        add(toolbar, BorderLayout.NORTH)
    }

    private fun createToolbar(): JComponent {
        val actionGroup = DefaultActionGroup().apply {
            add(RefreshServersAction())
            add(OpenMcpSettingsAction())
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("McpServerSelection", actionGroup, true)

        toolbar.targetComponent = this
        toolbar.component.border = JBUI.Borders.empty(2)

        return toolbar.component
    }

    private fun setupServerList() {
        serverList.apply {
            cellRenderer = McpServerListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 5

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val selectedIndex = locationToIndex(e.point)
                        if (selectedIndex >= 0) {
                            val serverItem = model.getElementAt(selectedIndex) as McpServerItem
                            attachServer(serverItem.serverDetails)
                        }
                    }
                }
            })
        }
    }

    private fun refreshServerList() {
        serverListModel.clear()

        val configuredServers = project.service<McpSettings>().state.servers
        if (configuredServers.isEmpty()) {
            removeAll()
            add(emptyPanel, BorderLayout.CENTER)
            revalidate()
            repaint()
            return
        }

        val conversationId = resolveConversationId()
        val attachedServerIds = if (conversationId != null) {
            try {
                service<McpSessionManager>().getSessionAttachments(conversationId)
                    .map { it.serverId }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
        } else {
            emptySet()
        }

        configuredServers.forEach { serverDetails ->
            val isAttached = attachedServerIds.contains(serverDetails.id.toString())
            val status = if (isAttached) {
                getServerStatus(serverDetails.id.toString())
            } else {
                ConnectionStatus.DISCONNECTED
            }

            serverListModel.addElement(McpServerItem(serverDetails, isAttached, status))
        }

        if (components.contains(emptyPanel)) {
            removeAll()
            val scrollPane = JBScrollPane(serverList).apply {
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                border = JBUI.Borders.empty()
            }
            add(scrollPane, BorderLayout.CENTER)
            add(createToolbar(), BorderLayout.NORTH)
            revalidate()
            repaint()
        }
    }

    private fun getServerStatus(serverId: String): ConnectionStatus {
        val conversationId = resolveConversationId()
        return if (conversationId != null) {
            try {
                val status = service<McpSessionManager>()
                    .getSessionAttachments(conversationId)
                    .find { it.serverId == serverId }?.connectionStatus
                status ?: ConnectionStatus.DISCONNECTED
            } catch (_: Exception) {
                ConnectionStatus.DISCONNECTED
            }
        } else {
            ConnectionStatus.DISCONNECTED
        }
    }

    private fun attachServer(serverDetails: McpServerDetailsState) {
        val conversationId = resolveConversationId()
        if (conversationId == null) {
            return
        }

        service<McpStatusBridge>()
            .attachServerAndUpdateUi(
                conversationId,
                serverDetails.id.toString(),
                serverDetails.name ?: "Unknown Server",
                userInputPanel
            )
            .whenComplete { _, _ -> refreshServerList() }
    }

    private fun resolveConversationId() =
        userInputPanel.getConversationId() ?: ConversationsState.getCurrentConversation()?.id

    private data class McpServerItem(
        val serverDetails: McpServerDetailsState,
        val isAttached: Boolean,
        val status: ConnectionStatus
    ) {
        val displayName: String get() = serverDetails.name ?: "Unknown Server"
    }

    private class McpServerListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            val serverItem = value as McpServerItem

            text = serverItem.displayName
            icon = Icons.MCP

            val statusText = when {
                serverItem.isAttached && serverItem.status == ConnectionStatus.CONNECTED -> " (Connected)"
                serverItem.isAttached && serverItem.status == ConnectionStatus.CONNECTING -> " (Connecting...)"
                serverItem.isAttached && serverItem.status == ConnectionStatus.ERROR -> " (Error)"
                serverItem.isAttached -> " (Attached)"
                else -> ""
            }

            text = "${serverItem.displayName}$statusText"

            if (!isSelected && !serverItem.isAttached) {
                foreground = UIUtil.getInactiveTextColor()
            }

            return this
        }
    }

    private inner class RefreshServersAction : AnAction(
        "Refresh", "Refresh server list", AllIcons.Actions.Refresh
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            refreshServerList()
        }
    }

    private inner class OpenMcpSettingsAction : AnAction(
        "Settings", "Open MCP settings", AllIcons.General.Settings
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val dataContext = DataManager.getInstance().getDataContext(this@McpServerSelectionPanel)
            val project = CommonDataKeys.PROJECT.getData(dataContext)
            if (project != null) {
                service<ShowSettingsUtil>().showSettingsDialog(project, McpConfigurable::class.java)
            }
        }
    }
}
