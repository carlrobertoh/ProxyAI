package ee.carlrobert.codegpt.toolwindow.agent.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.agent.ProxyAIAgent.loadProjectInstructions
import ee.carlrobert.codegpt.agent.history.AgentCheckpointConversationMapper
import ee.carlrobert.codegpt.agent.history.AgentCheckpointHistoryService
import ee.carlrobert.codegpt.agent.history.AgentHistoryThreadSummary
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.agents.SubagentsConfigurable
import ee.carlrobert.codegpt.tokens.TokenComputationService
import ee.carlrobert.codegpt.toolwindow.agent.AgentToolWindowContentManager
import ee.carlrobert.codegpt.toolwindow.agent.history.AgentHistoryListPanel
import ee.carlrobert.codegpt.toolwindow.ui.ResponseMessagePanel
import ee.carlrobert.codegpt.ui.UIUtil.createTextPane
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

class AgentToolWindowLandingPanel(private val project: Project) : ResponseMessagePanel() {

    companion object {
        private val logger = thisLogger()
    }

    private val historyService = project.service<AgentCheckpointHistoryService>()
    private val historyListPanel = AgentHistoryListPanel(defaultLimit = 5)
    private var refreshHistory = true

    private fun createLabel(text: String) = JBLabel(text)
    private fun createLink(text: String, onClick: () -> Unit) = ActionLink(text) { onClick() }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
    }

    init {
        historyListPanel.onOpen = { thread -> openCheckpointThread(thread) }
        historyListPanel.onLoadPage = { query, offset, limit, onResult ->
            loadHistoryPage(query, offset, limit, onResult)
        }
        addContent(buildContent())
        loadHistory()
    }

    private fun buildContent(): JPanel {
        return BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(0)
            add(createTextPane(welcomeMessage(), false), BorderLayout.NORTH)
            add(centerPanel(), BorderLayout.CENTER)
        }
    }

    private fun centerPanel(): JPanel {
        val panel = BorderLayoutPanel()
        panel.addToTop(topSectionsPanel())
        panel.addToCenter(previousChatsPanel())
        return panel
    }

    private fun topSectionsPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = LEFT_ALIGNMENT
        panel.add(actionsListPanel())
        panel.add(Box.createVerticalStrut(12))
        panel.add(projectInfoPanel())
        panel.add(Box.createVerticalStrut(12))
        return panel
    }

    private fun actionsListPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.emptyTop(8)
        panel.alignmentX = LEFT_ALIGNMENT

        panel.add(createLabel("Quick Actions"))
        panel.add(Box.createVerticalStrut(6))

        val itemsPanel = JPanel()
        itemsPanel.layout = BoxLayout(itemsPanel, BoxLayout.Y_AXIS)
        itemsPanel.border = JBUI.Borders.emptyLeft(16)
        itemsPanel.alignmentX = LEFT_ALIGNMENT

        itemsPanel.add(createLink("Manage Subagents") {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, SubagentsConfigurable::class.java)
        })
        itemsPanel.add(Box.createVerticalStrut(6))
        itemsPanel.add(createLink("Documentation & Tips") {
            try {
                Desktop.getDesktop().browse(URI("https://docs.tryproxy.io/agent"))
            } catch (e: Exception) {
                logger.error(e)
            }
        })

        panel.add(itemsPanel)
        return panel
    }

    private fun projectInfoPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty()
        panel.alignmentX = LEFT_ALIGNMENT

        panel.add(createLabel("Project Setup"))
        panel.add(Box.createVerticalStrut(6))

        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.border = JBUI.Borders.emptyLeft(16)
        container.alignmentX = LEFT_ALIGNMENT

        val vf = findProxyAiFile()
        if (vf == null) {
            container.add(createLabel("PROXYAI.md not found"))
            container.add(Box.createVerticalStrut(6))
            container.add(createLink("Initialize Project") { createProxyAiFile() })
        } else {
            val topRow = JPanel()
            topRow.layout = BoxLayout(topRow, BoxLayout.X_AXIS)
            topRow.alignmentX = LEFT_ALIGNMENT
            topRow.add(createLink("PROXYAI.md") { openProxyAiFile() })

            topRow.add(JBLabel(" • "))

            val fileSize = vf.length
            val fileSizeLabel = JBLabel(formatFileSize(fileSize))
            fileSizeLabel.foreground = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
            topRow.add(fileSizeLabel)

            topRow.add(JBLabel(" • "))

            val tokens = countFileTokens(vf)
            topRow.add(JBLabel("Tokens: "))
            val tokensLabel = JBLabel(formatTokens(tokens))
            tokensLabel.foreground = healthColor(tokens)
            topRow.add(tokensLabel)

            val bottomRow = JPanel()
            bottomRow.layout = BoxLayout(bottomRow, BoxLayout.X_AXIS)
            bottomRow.alignmentX = LEFT_ALIGNMENT

            val ts = vf.timeStamp
            val now = Instant.now()
            val fileTime = Instant.ofEpochMilli(ts)
            val zonedNow = now.atZone(ZoneId.systemDefault())
            val zonedFileTime = fileTime.atZone(ZoneId.systemDefault())

            val timeLabel = when {
                zonedFileTime.toLocalDate() == zonedNow.toLocalDate() -> {
                    val timeFormat =
                        DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
                    JBLabel("Modified today at ${timeFormat.format(fileTime)}")
                }

                zonedFileTime.toLocalDate() == zonedNow.minusDays(1).toLocalDate() -> {
                    val timeFormat =
                        DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
                    JBLabel("Modified yesterday at ${timeFormat.format(fileTime)}")
                }

                fileTime.isAfter(now.minusSeconds(7 * 24 * 60 * 60)) -> {
                    val dayFormat =
                        DateTimeFormatter.ofPattern("EEEE").withZone(ZoneId.systemDefault())
                    val timeFormat =
                        DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
                    JBLabel(
                        "Modified on ${dayFormat.format(fileTime)} at ${
                            timeFormat.format(
                                fileTime
                            )
                        }"
                    )
                }

                zonedFileTime.toLocalDate().year == zonedNow.toLocalDate().year -> {
                    val dateFormat =
                        DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault())
                    val timeFormat =
                        DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
                    JBLabel(
                        "Modified on ${dateFormat.format(fileTime)} at ${
                            timeFormat.format(
                                fileTime
                            )
                        }"
                    )
                }

                else -> {
                    val dateFormat =
                        DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
                    val timeFormat =
                        DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
                    JBLabel(
                        "Modified on ${dateFormat.format(fileTime)} at ${
                            timeFormat.format(
                                fileTime
                            )
                        }"
                    )
                }
            }
            timeLabel.foreground = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
            bottomRow.add(timeLabel)

            container.add(topRow)
            container.add(Box.createVerticalStrut(2))
            container.add(bottomRow)
        }

        panel.add(container)
        return panel
    }

    private fun previousChatsPanel(): JPanel {
        val panel = BorderLayoutPanel().apply {
            border = JBUI.Borders.empty()
        }
        panel.addToTop(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            add(createLabel("Previous Chats"))
            add(Box.createVerticalStrut(6))
        })
        panel.addToCenter(historyListPanel)
        return panel
    }

    private fun findProxyAiFile() =
        project.basePath?.let {
            LocalFileSystem.getInstance().findFileByPath(Path.of(it, "PROXYAI.md").toString())
        }

    private fun createProxyAiFile() {
        val basePath = project.basePath ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath)
                ?: return@runWriteCommandAction
            val existing = dir.findChild("PROXYAI.md")
            val vf = existing ?: dir.createChildData(this, "PROXYAI.md")
            vf.setBinaryContent(defaultProxyAiContent().toByteArray(StandardCharsets.UTF_8))
        }
        refresh()
    }

    private fun openProxyAiFile() {
        val vf = findProxyAiFile() ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun refresh() {
        removeAll()
        addContent(buildContent())
        loadHistory()
        revalidate()
        repaint()
    }

    private fun loadHistory() {
        refreshHistory = true
        historyListPanel.reload()
    }

    private fun loadHistoryPage(
        query: String,
        offset: Int,
        limit: Int,
        onResult: (List<AgentHistoryThreadSummary>, Boolean, Int) -> Unit
    ) {
        val shouldRefresh = offset == 0 && refreshHistory
        ApplicationManager.getApplication().executeOnPooledThread {
            val page = runCatching {
                runBlocking {
                    historyService.listThreadsPage(
                        query = query,
                        offset = offset,
                        limit = limit,
                        refresh = shouldRefresh
                    )
                }
            }
                .onFailure { logger.warn("Failed to load checkpoint history", it) }
                .getOrNull()

            runInEdt {
                if (page != null) {
                    refreshHistory = false
                }
                onResult(page?.items.orEmpty(), page?.hasMore == true, page?.total ?: 0)
            }
        }
    }

    private fun openCheckpointThread(thread: AgentHistoryThreadSummary) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val conversation = runCatching {
                val checkpoint = runBlocking { historyService.loadCheckpoint(thread.latest) }
                    ?: return@executeOnPooledThread
                AgentCheckpointConversationMapper.toConversation(
                    checkpoint = checkpoint,
                    projectInstructions = loadProjectInstructions(project.basePath)
                )
            }.onFailure {
                logger.warn("Failed to open checkpoint thread ${thread.agentId}", it)
            }.getOrNull() ?: return@executeOnPooledThread

            ApplicationManager.getApplication().invokeLater {
                project.service<AgentToolWindowContentManager>()
                    .openCheckpointConversation(thread, conversation)
            }
        }
    }

    private fun welcomeMessage(): String {
        val name = GeneralSettings.getCurrentState().displayName
        return """
            <html>
            <p style="margin-top: 4px; margin-bottom: 4px;">
            Hi <strong>$name</strong>, I'm <strong>ProxyAI Agent</strong>! I can break down complex tasks, modify your codebase, navigate project files, execute terminal commands, and orchestrate multi-phase development workflows.
            </p>
            </html>
        """.trimIndent()
    }

    private fun defaultProxyAiContent() = """
        # ProxyAI Instructions

        Describe goals, conventions, risky areas, and review rules here.
    """.trimIndent()

    private fun countFileTokens(vf: VirtualFile): Int {
        val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull() ?: ""
        return TokenComputationService.getInstance().countTextTokens(text)
    }

    private fun formatTokens(n: Int): String =
        if (n < 1000) n.toString() else String.format("%.1fk", n / 1000.0)

    private fun healthColor(tokens: Int): Color {
        val green = Color(76, 175, 80)
        val yellow = Color(255, 193, 7)
        val red = Color(244, 67, 54)
        if (tokens <= 4096) return green
        if (tokens <= 8192) return lerpColor(green, yellow, (tokens - 4096f) / (8192f - 4096f))
        if (tokens <= 16384) return lerpColor(yellow, red, (tokens - 8192f) / (16384f - 8192f))
        return red
    }

    private fun lerpColor(a: Color, b: Color, tRaw: Float): Color {
        val t = tRaw.coerceIn(0f, 1f)
        val r = (a.red + (b.red - a.red) * t).toInt()
        val g = (a.green + (b.green - a.green) * t).toInt()
        val bl = (a.blue + (b.blue - a.blue) * t).toInt()
        return Color(r, g, bl)
    }
}
