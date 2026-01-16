package ee.carlrobert.codegpt.toolwindow.agent.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.OpenFileAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.agent.rollback.*
import ee.carlrobert.codegpt.toolwindow.agent.ui.renderer.ChangeColors
import ee.carlrobert.codegpt.toolwindow.agent.ui.renderer.lineDiffStats
import ee.carlrobert.codegpt.ui.IconActionButton
import kotlinx.coroutines.*
import java.awt.Dimension
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*

/**
 * Panel showing file operations performed by the agent with rollback controls.
 *
 */
class RollbackPanel(
    private val project: Project,
    private val sessionId: String,
    private val onRollbackComplete: () -> Unit
) : BorderLayoutPanel() {
    private val rollbackService = RollbackService.getInstance(project)
    private val titleLabel = JBLabel()
    private val timeLabel = JBLabel()
    private val changesPanel = JPanel()
    private val scrollPane = JScrollPane(changesPanel)
    private val rollbackAllLink = createRollbackAllLink()
    private val keepAllLink = createKeepAllLink()
    private val diffStatsCache = ConcurrentHashMap<String, Triple<Int, Int, Int>>()
    private val diffDataCache = ConcurrentHashMap<String, RollbackDiffData>()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        setupUI()
    }

    private fun setupUI() {
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(titleLabel.apply {
                font = font.deriveFont(java.awt.Font.BOLD)
            })
            add(timeLabel.apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })
        }

        val topPanel = BorderLayoutPanel().apply {
            addToLeft(headerPanel)
            val actionLinksPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(keepAllLink)
                add(Box.createHorizontalStrut(16))
                add(rollbackAllLink)
            }
            addToRight(actionLinksPanel)
            border = JBUI.Borders.empty(6, 0)
        }

        changesPanel.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        scrollPane.apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            border = null
            preferredSize = Dimension(0, 240)
        }

        addToTop(topPanel)
        addToCenter(
            BorderLayoutPanel().apply {
                addToCenter(scrollPane)
            }
        )
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(0, 0, 8, 0)
        )

        refreshOperations()
    }

    fun refreshOperations() {
        refreshOperationsAsync()
    }

    private fun refreshOperationsAsync() {
        backgroundScope.launch {
            val snapshot = rollbackService.getSnapshot(sessionId)
            val changes = snapshot?.changes.orEmpty()
                .filter { rollbackService.isDisplayable(it.path) }
                .sortedBy { it.path }

            withContext(Dispatchers.EDT) {
                refreshOperationsUI(changes, snapshot?.completedAt)
            }
        }
    }

    private fun refreshOperationsUI(changes: List<FileChange>, completedAt: Instant?) {
        isVisible = changes.isNotEmpty()
        if (changes.isEmpty()) {
            titleLabel.text = "Changes"
            timeLabel.text = ""
            changesPanel.removeAll()
            rollbackAllLink.isVisible = false
            keepAllLink.isVisible = false
            revalidate()
            repaint()
            return
        }

        val timeText = completedAt?.let { formatTime(it) } ?: ""
        titleLabel.text = "Changes (${changes.size})"
        timeLabel.text = if (timeText.isNotBlank()) "• $timeText" else ""

        preloadDiffStats(changes)

        changesPanel.removeAll()
        changes.forEachIndexed { index, change ->
            if (index > 0) changesPanel.add(Box.createVerticalStrut(4))
            changesPanel.add(createChangeRow(change))
        }
        updateScrollPaneSizing()
        rollbackAllLink.isVisible = true
        keepAllLink.isVisible = true

        revalidate()
        repaint()
    }

    private fun handleRollback() {
        CoroutineScope(Dispatchers.Main).launch {
            val result = rollbackService.rollbackSession(sessionId)

            withContext(Dispatchers.Main) {
                when (result) {
                    is RollbackResult.Success -> {
                        refreshOperations()
                        onRollbackComplete()
                    }

                    is RollbackResult.Failure -> {
                        Messages.showErrorDialog(project, result.message, "Rollback Failed")
                    }
                }
            }
        }
    }

    private fun createChangeRow(change: FileChange): JComponent {
        val row = BorderLayoutPanel().apply {
            isOpaque = true
            background = JBUI.CurrentTheme.List.background(false, false)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4, 8)
            )
        }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
        }
        left.add(changeLabel(change))
        left.add(fileNameComponent(change))
        left.add(filePathLabel(change))
        addDiffStats(change, left)

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
        }
        openDiffAction(change).let { actions.add(it) }
        actions.add(rollbackAction(change))

        row.addToLeft(left)
        row.addToRight(actions)
        return row
    }

    private fun changeLabel(change: FileChange): JBLabel {
        val (text, color) = when (change.kind) {
            ChangeKind.ADDED -> "+" to ChangeColors.inserted
            ChangeKind.DELETED -> "-" to ChangeColors.deleted
            ChangeKind.MODIFIED -> "~" to ChangeColors.modified
            ChangeKind.MOVED -> "~" to ChangeColors.modified
        }
        return JBLabel(text).apply {
            foreground = color
            font = JBUI.Fonts.smallFont()
        }
    }

    private fun fileNameComponent(change: FileChange): JComponent {
        val display = displayFileName(change.path)
        val file = LocalFileSystem.getInstance().findFileByPath(change.path)
        return if (file != null && change.kind != ChangeKind.DELETED) {
            ActionLink(display) {
                OpenFileAction.openFile(file, project)
            }.apply {
                font = JBUI.Fonts.label().asBold()
            }
        } else {
            JBLabel(display).apply {
                font = JBUI.Fonts.label().asBold()
            }
        }
    }

    private fun filePathLabel(change: FileChange): JBLabel {
        val display = displayPath(change.path, change)
        return JBLabel(display).apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            font = JBUI.Fonts.smallFont()
            toolTipText = display
        }
    }

    private fun openDiffAction(change: FileChange): JComponent {
        return IconActionButton(
            object : AnAction("Open Diff", "Open diff view", AllIcons.Actions.Diff) {
                override fun actionPerformed(e: AnActionEvent) {
                    openDiffForPath(change.path)
                }
            },
            "OPEN_DIFF"
        )
    }

    private fun rollbackAction(change: FileChange): JComponent {
        return IconActionButton(
            object : AnAction("Rollback File", "Rollback this file", AllIcons.Actions.Undo) {
                override fun actionPerformed(e: AnActionEvent) {
                    rollbackFile(change.path)
                }
            },
            "ROLLBACK_FILE"
        )
    }

    private fun displayPath(path: String, change: FileChange): String {
        val originalPath = change.originalPath?.let { toRelativePath(it) }
        val truncatedPath = truncatePath(toRelativePath(path))
        val truncatedOriginal = originalPath?.let { truncatePath(it) }

        return if (change.kind == ChangeKind.MOVED && originalPath != null) {
            "$truncatedPath (renamed from $truncatedOriginal)"
        } else {
            truncatedPath
        }
    }

    private fun toRelativePath(path: String): String {
        val base = project.basePath?.replace("\\", "/")
        val normalized = path.replace("\\", "/")
        return if (base != null && normalized.startsWith(base)) {
            normalized.removePrefix(base).trimStart('/')
        } else {
            normalized
        }
    }

    private fun truncatePath(path: String, maxLength: Int = 80): String {
        return if (path.length > maxLength) {
            "..." + path.takeLast(maxLength)
        } else {
            path
        }
    }

    private fun displayFileName(path: String): String {
        val normalized = path.replace("\\", "/")
        return normalized.substringAfterLast('/')
    }

    private fun addDiffStats(change: FileChange, container: JPanel) {
        val stats = diffStatsCache[change.path] ?: return
        val (ins, del, mod) = stats
        if (ins + del + mod == 0) return
        container.add(colorLabel("+$ins", ChangeColors.inserted))
        container.add(colorLabel("-$del", ChangeColors.deleted))
        container.add(colorLabel("~$mod", ChangeColors.modified))
    }

    private fun colorLabel(text: String, color: JBColor): JBLabel =
        JBLabel(text).apply {
            foreground = color
            font = JBUI.Fonts.smallFont()
        }

    private fun formatTime(instant: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        return "Last run at ${formatter.format(instant)}"
    }

    private fun updateScrollPaneSizing() {
        val maxVisibleItems = 5
        val componentList = changesPanel.components.toList()
        var visibleRows = 0
        var height = 0
        componentList.forEach { component ->
            if (visibleRows >= maxVisibleItems) return@forEach
            height += component.preferredSize.height
            if (component is BorderLayoutPanel) {
                visibleRows += 1
            }
        }
        if (height == 0) {
            scrollPane.preferredSize = Dimension(0, 0)
            return
        }
        scrollPane.preferredSize = Dimension(0, height)
        scrollPane.maximumSize = Dimension(Int.MAX_VALUE, height)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun createRollbackAllLink(): JComponent {
        return ActionLink("Rollback all") { handleRollback() }
    }

    private fun createKeepAllLink(): JComponent {
        return ActionLink("Keep all") { handleKeepAll() }
    }

    private fun handleKeepAll() {
        rollbackService.clearSnapshot(sessionId)
        refreshOperations()
        onRollbackComplete()
    }

    private fun rollbackFile(path: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = rollbackService.rollbackFile(sessionId, path)

            withContext(Dispatchers.Main) {
                when (result) {
                    is RollbackResult.Success -> {
                        refreshOperations()
                        onRollbackComplete()
                    }

                    is RollbackResult.Failure -> {
                        Messages.showErrorDialog(project, result.message, "Rollback Failed")
                    }
                }
            }
        }
    }

    private fun preloadDiffStats(changes: List<FileChange>) {
        changes.forEach { change ->
            if (diffStatsCache.containsKey(change.path)) return@forEach
            backgroundScope.launch {
                val diffData = rollbackService.getDiffData(sessionId, change.path) ?: return@launch
                diffDataCache[change.path] = diffData
                diffStatsCache[change.path] =
                    lineDiffStats(diffData.beforeText, diffData.afterText)
                withContext(Dispatchers.EDT) {
                    refreshOperations()
                }
            }
        }
    }

    private fun openDiffForPath(path: String) {
        val cached = diffDataCache[path]
        if (cached != null) {
            openDiff(cached)
            return
        }
        backgroundScope.launch {
            val diffData = rollbackService.getDiffData(sessionId, path) ?: return@launch
            diffDataCache[path] = diffData
            withContext(Dispatchers.Main) {
                openDiff(diffData)
            }
        }
    }

    private fun openDiff(diffData: RollbackDiffData) {
        val contentFactory = DiffContentFactory.getInstance()
        val before = contentFactory.create(diffData.beforeText)
        val after = contentFactory.create(diffData.afterText)
        val title = "Agent change: ${displayFileName(diffData.path)}"
        val request = SimpleDiffRequest(title, before, after, "Before", "After")
        DiffManager.getInstance().showDiff(project, request)
    }
}
