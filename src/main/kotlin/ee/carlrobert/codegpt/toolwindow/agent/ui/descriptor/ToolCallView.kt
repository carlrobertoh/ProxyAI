package ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor

import com.intellij.ide.actions.OpenFileAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.agent.tools.IntelliJSearchTool
import ee.carlrobert.codegpt.agent.tools.AskUserQuestionTool
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

class ToolCallView(
    private var descriptor: ToolCallDescriptor
) : JBPanel<ToolCallView>() {

    private var headerPanel = ToolCallHeaderPanel(descriptor)
    private val streamingPanel = ToolCallStreamingPanel()

    init {
        layout = BorderLayout()
        isOpaque = false

        border = JBUI.Borders.empty()

        add(headerPanel, BorderLayout.NORTH)
        add(streamingPanel, BorderLayout.CENTER)
    }

    fun complete(success: Boolean, result: Any?) {
        headerPanel.updateCompletionStatus(result)
        when (result) {
            is AskUserQuestionTool.Result.Success -> {
                val compactLines = result.answers.entries.map { (k, v) -> "$k: $v" }
                streamingPanel.showCompactInfo(compactLines)
            }
            else -> streamingPanel.onCompletion()
        }
    }

    fun appendStreamingLine(text: String, isError: Boolean) {
        if (descriptor.supportsStreaming) {
            streamingPanel.appendLine(text, isError)
        }
    }

    fun getDescriptor(): ToolCallDescriptor = descriptor

    fun refreshDescriptor(newDescriptor: ToolCallDescriptor) {
        this.descriptor = newDescriptor
        remove(headerPanel)
        headerPanel = ToolCallHeaderPanel(descriptor)
        add(headerPanel, BorderLayout.NORTH)
        revalidate()
        repaint()
    }
}

private class ToolCallHeaderPanel(
    private val descriptor: ToolCallDescriptor
) : JBPanel<ToolCallHeaderPanel>() {

    private val leftRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
    private var fileLink: ActionLink? = null

    init {
        layout = BorderLayout()
        isOpaque = false

        buildHeaderContent()
        add(leftRow, BorderLayout.WEST)
    }

    private fun buildHeaderContent() {
        val prefixLabel = JBLabel(
            if (descriptor.titlePrefix.isNotEmpty()) "${descriptor.titlePrefix} " else "",
            descriptor.icon,
            SwingConstants.LEFT
        ).withFont(JBFont.label()).apply {
            descriptor.prefixColor?.let { color ->
                foreground = color
            }
        }
        leftRow.add(prefixLabel)

        when {
            descriptor.fileLink != null -> addFileLink()
            else -> addRegularContent()
        }

        addSecondaryBadges()

        if (descriptor.actions.isNotEmpty()) {
            addActionLinks()
        }
    }

    private fun addFileLink() {
        val fileLink = descriptor.fileLink!!
        val link = ActionLink(fileLink.displayName) {
            val project = getProject()
            if (project != null) {
                val vf = LocalFileSystem.getInstance().findFileByPath(fileLink.path)
                if (vf != null) OpenFileAction.openFile(vf, project)
            }
        }.apply {
            toolTipText = fileLink.path
            setExternalLinkIcon()
            isEnabled = fileLink.enabled
        }

        this.fileLink = link
        leftRow.add(link)
    }

    private fun addRegularContent() {
        val content = JBLabel(descriptor.titleMain)
        if (!descriptor.tooltip.isNullOrBlank()) {
            content.toolTipText = descriptor.tooltip
        }
        leftRow.add(content)

        // Show IntelliJSearch parameter chips for compact rows for parity with main cards
        addSearchParametersIfAny()
    }

    private fun addSecondaryBadges() {
        descriptor.secondaryBadges.forEach { badge ->
            val isMatchesBadge = badge.text.contains("matches", ignoreCase = true)
            if (isMatchesBadge && descriptor.result != null) {
                val link = ActionLink(badge.text) {
                    val content = when (val res = descriptor.result) {
                        is IntelliJSearchTool.Result -> res.output
                        is String -> res
                        else -> null
                    }
                    content?.let { showSearchResultsDialog(it) }
                }.apply {
                    font = JBUI.Fonts.smallFont()
                }
                leftRow.add(link.apply { border = JBUI.Borders.emptyLeft(4) })
            } else {
                leftRow.add(JBLabel(badge.text).withFont(JBFont.small()).apply {
                    foreground = badge.color
                    if (badge.tooltip != null) {
                        toolTipText = badge.tooltip
                    }
                    border = JBUI.Borders.emptyLeft(4)
                })
            }
        }
    }

    private fun showSearchResultsDialog(content: String) {
        val dialog = JDialog().apply {
            title = "Search Results"
            isModal = true
        }

        val textArea = JTextArea(content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.smallFont()
        }

        val scrollPane = JScrollPane(textArea).apply {
            preferredSize = JBUI.size(700, 400)
            border =
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
        }

        val footerPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JButton("Copy").apply {
                addActionListener {
                    val selection = StringSelection(content)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        selection,
                        null
                    )
                }
            })
            add(JButton("Close").apply {
                addActionListener { dialog.dispose() }
            })
        }

        dialog.contentPane = BorderLayoutPanel().apply {
            add(scrollPane, BorderLayout.CENTER)
            add(footerPanel, BorderLayout.SOUTH)
        }
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    fun updateCompletionStatus(result: Any?) {
        descriptor.fileLink?.let { fileLink ->
            if (!fileLink.enabled && result != null) {
                this.fileLink?.isEnabled = true
            }
        }

        revalidate()
        repaint()
    }

    private fun addActionLinks() {
        descriptor.actions.forEach { action ->
            val link = ActionLink("[${action.name}]") { action.action(this@ToolCallHeaderPanel) }.apply {
                font = JBFont.small()
            }
            leftRow.add(JBLabel(" "))
            leftRow.add(link)
        }
    }

    private fun addSearchParametersIfAny() {
        val args = descriptor.args
        if (args is IntelliJSearchTool.Args) {
            val params = buildList {
                if (args.caseSensitive == true) add("case")
                if (args.regex == true) add("regex")
                if (args.wholeWords == true) add("words")
                args.context?.let { c ->
                    if (c.isNotBlank() && !c.equals(
                            "ANY",
                            true
                        )
                    ) add("context: $c")
                }
                args.fileType?.let { ft -> if (ft.isNotBlank()) add("type: $ft") }
                args.outputMode?.let { om ->
                    if (om.isNotBlank() && !om.equals(
                            "content",
                            true
                        )
                    ) add("out: $om")
                }
                args.limit?.let { lim -> add("limit: $lim") }
            }.joinToString(" · ")

            if (params.isNotBlank()) {
                leftRow.add(JBLabel(" ($params)"))
            }
        }
    }

    private fun getProject(): Project? {
        return descriptor.projectId?.let { projectId ->
            ProjectManager.getInstance().openProjects.find { it.locationHash == projectId }
        } ?: ProjectManager.getInstance().openProjects.firstOrNull()
    }
}

/**
 * Handles streaming output display.
 */
private class ToolCallStreamingPanel : JBPanel<ToolCallStreamingPanel>() {

    private val contentPanel = JBPanel<JBPanel<*>>().apply {
        isOpaque = false
        layout = FlowLayout(FlowLayout.LEFT, 0, 0)
        border = JBUI.Borders.empty(2, 20, 0, 0)
        isVisible = false
    }

    private val streamingLabel = JBLabel("")
    private val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(12)
        add(ActionLink("Show details") { showDetailsDialog() }.apply {
            font = JBUI.Fonts.smallFont()
        })
        add(ActionLink("Copy") { copyToClipboard() }.apply {
            font = JBUI.Fonts.smallFont()
        })
        isVisible = false
    }

    private val streamingTail: ArrayDeque<Pair<String, Boolean>> = ArrayDeque()
    private val streamingAllLines: ArrayDeque<Pair<String, Boolean>> = ArrayDeque()

    companion object {
        private const val MAX_TAIL_LINES = 3
        private const val MAX_DETAIL_LINES = 500
    }

    init {
        layout = BorderLayout()
        isOpaque = false

        add(contentPanel, BorderLayout.CENTER)
        add(actionsPanel, BorderLayout.SOUTH)
    }

    fun showCompactInfo(lines: List<String>) {
        if (lines.isEmpty()) {
            contentPanel.isVisible = false
            actionsPanel.isVisible = false
            return
        }
        val html = buildString {
            append("<html><body style='color:#808080'>")
            lines.forEachIndexed { i, line ->
                val safe = sanitizeHtml(line.take(300))
                append(safe)
                if (i < lines.size - 1) append("<br>")
            }
            append("</body></html>")
        }
        streamingLabel.font = JBUI.Fonts.smallFont()
        streamingLabel.text = html
        contentPanel.removeAll()
        contentPanel.add(streamingLabel)
        contentPanel.isVisible = true
        actionsPanel.isVisible = false
        revalidate()
        repaint()
    }

    fun appendLine(text: String, isError: Boolean) {
        if (text.isBlank()) return

        streamingTail.addLast(text to isError)
        while (streamingTail.size > MAX_TAIL_LINES) {
            streamingTail.removeFirst()
        }

        streamingAllLines.addLast(text to isError)
        while (streamingAllLines.size > MAX_DETAIL_LINES) {
            streamingAllLines.removeFirst()
        }

        updateDisplay()
    }

    fun onCompletion() {
        contentPanel.isVisible = streamingTail.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun updateDisplay() {
        val html = buildTailHtml()
        val font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.smallFont().size))

        streamingLabel.font = font
        streamingLabel.text = html

        contentPanel.removeAll()
        contentPanel.add(streamingLabel)
        contentPanel.isVisible = true
        actionsPanel.isVisible = true

        revalidate()
        repaint()
    }

    private fun buildTailHtml(): String {
        return buildString {
            append("<html><body style='width: 400px'>")
            streamingTail.forEachIndexed { idx, (line, isError) ->
                val safeText = sanitizeHtml(line.take(200))
                val color = if (isError) "#ff5555" else "#808080"
                append("<span style='color:$color'>$safeText</span>")
                if (idx < streamingTail.size - 1) append("<br>")
            }
            append("</body></html>")
        }
    }

    private fun sanitizeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun copyToClipboard() {
        val content = buildString {
            streamingAllLines.forEachIndexed { i, (line, _) ->
                append(line)
                if (i < streamingAllLines.size - 1) append('\n')
            }
        }
        val selection = StringSelection(content)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    }

    private fun showDetailsDialog() {
        val dialog = JDialog().apply {
            title = "Streaming Output"
            isModal = true
        }

        val content = buildString {
            streamingAllLines.forEachIndexed { i, (line, _) ->
                append(line)
                if (i < streamingAllLines.size - 1) append('\n')
            }
        }

        val textArea = JTextArea(content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.smallFont()
        }

        val scrollPane = JScrollPane(textArea).apply {
            preferredSize = JBUI.size(700, 400)
            border =
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
        }

        val footerPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JButton("Copy").apply {
                addActionListener { copyToClipboard() }
            })
            add(JButton("Close").apply {
                addActionListener { dialog.dispose() }
            })
        }

        dialog.contentPane = BorderLayoutPanel().apply {
            add(scrollPane, BorderLayout.CENTER)
            add(footerPanel, BorderLayout.SOUTH)
        }
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }
}
