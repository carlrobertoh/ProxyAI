package ee.carlrobert.codegpt.toolwindow.ui.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.llm.client.openai.completion.response.ToolCall
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import kotlin.math.min

class CompactToolPanel(
    private val toolCall: ToolCall,
    private val serverName: String,
    private val onApprove: (autoApprove: Boolean) -> Unit,
    private val onReject: () -> Unit,
) : JBPanel<CompactToolPanel>() {

    private var isExpanded = false
    var isCompleted = false
        private set
    private var status: CompletionStatus? = null

    val toolCallId: String?
        get() = toolCall.id

    private val parameterPreview: String
    private val fullParameters: Map<String, Any>?

    private val mainPanel = JBPanel<JBPanel<*>>()
    private val detailsPanel = JBPanel<JBPanel<*>>()
    private val statusLabel = JBLabel()

    enum class CompletionStatus {
        APPROVED, REJECTED, EXECUTING, COMPLETED, FAILED
    }

    companion object {
        private const val SEPARATOR_TEXT = " | "
        private const val AUTO_TOOLTIP_TEXT = "Auto-approve for this session"
    }

    init {
        val (preview, full) = parseParameters()
        parameterPreview = preview
        fullParameters = full

        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.empty()

        setupUI()
        setupKeyboardShortcuts()
    }

    private fun parseParameters(): Pair<String, Map<String, Any>?> {
        return try {
            val mapper = ObjectMapper()
            val args =
                mapper.readValue(toolCall.function.arguments, Map::class.java) as Map<String, Any>

            val name = toolCall.function.name.lowercase()
            val preview = when {
                args.isEmpty() -> ""
                name.contains("bash") -> {
                    val cmd = (args["command"] as? String)?.trim()
                    if (!cmd.isNullOrEmpty()) "command: \"${cmd.take(80)}${if (cmd.length > 80) "..." else ""}\"" else
                        args.entries.take(2).joinToString(", ") { (k, v) -> "$k: ${formatPreviewValue(v)}" }
                }
                name.startsWith("read") -> {
                    val path = (args["file_path"] as? String) ?: (args["path"] as? String)
                    val off = (args["offset"] as? Number)?.toInt()
                    val lim = (args["limit"] as? Number)?.toInt()
                    buildString {
                        path?.let { append("file: ${it}") }
                        if (off != null || lim != null) {
                            if (isNotEmpty()) append(", ")
                            append("range: ")
                            append(off?.toString() ?: "1")
                            append("..")
                            append(lim?.toString() ?: "")
                        }
                    }.ifEmpty {
                        args.entries.take(2).joinToString(", ") { (k, v) -> "$k: ${formatPreviewValue(v)}" }
                    }
                }
                name.contains("grep") || name.contains("search") -> {
                    val pat = (args["pattern"] as? String)
                    val path = (args["path"] as? String)
                    buildString {
                        pat?.let { append("pattern: \"${it.take(40)}${if (it.length > 40) "..." else ""}\"") }
                        if (!path.isNullOrEmpty()) {
                            if (isNotEmpty()) append(", ")
                            append("in: $path")
                        }
                    }.ifEmpty {
                        args.entries.take(2).joinToString(", ") { (k, v) -> "$k: ${formatPreviewValue(v)}" }
                    }
                }
                name.startsWith("write") || name.contains("edit") -> {
                    val path = (args["file_path"] as? String) ?: (args["path"] as? String)
                    val content = (args["content"] as? String)
                    buildString {
                        path?.let { append("file: $it") }
                        if (!content.isNullOrEmpty()) {
                            if (isNotEmpty()) append(", ")
                            append("content: ${content.length} chars")
                        }
                    }.ifEmpty {
                        args.entries.take(2).joinToString(", ") { (k, v) -> "$k: ${formatPreviewValue(v)}" }
                    }
                }
                else -> {
                    args.entries.take(2).joinToString(", ") { (k, v) ->
                        "$k: ${formatPreviewValue(v)}"
                    } + if (args.size > 2) ", ..." else ""
                }
            }

            preview to args
        } catch (_: Exception) {
            val raw = toolCall.function.arguments
            val preview = if (raw.isNullOrEmpty() || raw == "{}") "" else raw.take(50) + "..."
            preview to null
        }
    }

    private fun setupUI() {
        mainPanel.apply {
            layout = BorderLayout()
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 4, 0)

            setupMainContent()

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!isCompleted && e.clickCount == 1) {
                        toggleExpanded()
                    }
                }

                override fun mouseEntered(e: MouseEvent) {
                    if (!isCompleted) {
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        background = JBUI.CurrentTheme.ActionButton.hoverBackground()
                        isOpaque = true
                        repaint()
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    cursor = Cursor.getDefaultCursor()
                    isOpaque = false
                    repaint()
                }
            })
        }

        detailsPanel.apply {
            isVisible = false
            layout = BorderLayout()
            isOpaque = false
            border = JBUI.Borders.empty(0, 32, 8, 0)

            setupDetailsContent()
        }

        add(mainPanel, BorderLayout.NORTH)
        add(detailsPanel, BorderLayout.CENTER)
    }

    private fun setupMainContent() {
        mainPanel.layout = BorderLayout()

        val infoPanel = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.LEFT, 0, 0)
            isOpaque = false
        }

        infoPanel.add(Box.createHorizontalStrut(0))

        val toolNameLabel = JBLabel(toolCall.function.name).apply {
            font = JBUI.Fonts.label().asBold()
        }
        infoPanel.add(toolNameLabel)

        infoPanel.add(JBLabel(" • ").apply { foreground = JBColor.gray })

        infoPanel.add(JBLabel(serverName).apply {
            foreground = JBColor.gray
        })

        if (parameterPreview.isNotEmpty()) {
            infoPanel.add(JBLabel(" • ").apply { foreground = JBColor.gray })
            val paramLabel = JBLabel(parameterPreview).apply {
                foreground = JBColor.gray
                toolTipText = McpParameterPreview.generateTooltip(toolCall.function.arguments)
            }
            infoPanel.add(paramLabel)
        }

        if (fullParameters != null && fullParameters.isNotEmpty()) {
            infoPanel.add(Box.createHorizontalStrut(4))
            infoPanel.add(JBLabel(AllIcons.General.ArrowDown).apply {
                foreground = JBColor.gray
            })
        }

        mainPanel.add(infoPanel, BorderLayout.CENTER)

        if (!isCompleted) {
            val actionsPanel = setupActionLinks()
            mainPanel.add(actionsPanel, BorderLayout.EAST)
        } else {
            statusLabel.font = JBUI.Fonts.smallFont()
            statusLabel.foreground = when (status) {
                CompletionStatus.COMPLETED -> JBColor.BLUE
                CompletionStatus.FAILED -> JBColor.RED
                CompletionStatus.APPROVED -> JBColor.GREEN
                CompletionStatus.REJECTED -> JBColor.RED
                CompletionStatus.EXECUTING -> JBColor.GRAY
                else -> JBColor.GRAY
            }
            mainPanel.add(statusLabel, BorderLayout.EAST)
        }
    }

    private fun setupActionLinks(): JPanel {
        val actionsPanel = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.RIGHT, 4, 0)
            isOpaque = false
        }

        actionsPanel.add(ActionLink("Yes") {
            handleApproval(false)
        })

        actionsPanel.add(JBLabel(SEPARATOR_TEXT).apply { foreground = JBColor.gray })

        actionsPanel.add(ActionLink("No") {
            handleRejection()
        })

        return actionsPanel
    }

    private fun setupDetailsContent() {
        if (fullParameters == null || fullParameters.isEmpty()) return

        val paramsPanel = JBPanel<JBPanel<*>>().apply {
            layout = GridBagLayout()
            isOpaque = false
        }

        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(2, 0)
            gridx = 0
            gridy = 0
        }

        fullParameters.forEach { (key, value) ->
            gbc.gridx = 0
            gbc.weightx = 0.0
            paramsPanel.add(JBLabel("$key:").apply {
                foreground = JBColor.gray
                font = JBUI.Fonts.smallFont()
            }, gbc)

            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.insets = JBUI.insets(2, 8, 2, 0)

            val valueLabel = createValueLabel(value)
            paramsPanel.add(valueLabel, gbc)

            gbc.gridy++
            gbc.insets = JBUI.insets(2, 0)
        }

        detailsPanel.add(paramsPanel, BorderLayout.NORTH)
    }

    private fun createValueLabel(value: Any): JComponent {
        val text = formatDetailValue(value)

        return if (text.length > 100 || text.contains('\n')) {
            JBTextArea(text).apply {
                isEditable = false
                font = JBUI.Fonts.smallFont()
                background = UIUtil.getPanelBackground()
                foreground = JBColor.foreground()
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty()
                rows = min(3, text.lines().size)
            }
        } else {
            JBLabel(text).apply {
                font = JBUI.Fonts.smallFont()
            }
        }
    }

    private fun formatPreviewValue(value: Any): String {
        return when (value) {
            is String -> if (value.length > 20) "\"${value.take(20)}...\"" else "\"$value\""
            is List<*> -> "[${value.size}]"
            is Map<*, *> -> "{${value.size}}"
            else -> value.toString().take(20)
        }
    }

    private fun formatDetailValue(value: Any): String {
        return when (value) {
            is String -> value
            is List<*> -> value.joinToString("\n") { "• ${it.toString()}" }
            is Map<*, *> -> value.entries.joinToString("\n") { (k, v) -> "$k: $v" }
            else -> value.toString()
        }
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded

        val infoPanel = (mainPanel.getComponent(0) as? JPanel) ?: return
        infoPanel.components
            .filterIsInstance<JBLabel>()
            .lastOrNull { it.icon == AllIcons.General.ArrowDown || it.icon == AllIcons.General.ArrowUp }
            ?.icon = if (isExpanded) AllIcons.General.ArrowUp else AllIcons.General.ArrowDown

        if (isExpanded) {
            detailsPanel.isVisible = true
            val targetHeight = detailsPanel.preferredSize.height
            detailsPanel.preferredSize = detailsPanel.preferredSize.apply { height = 0 }
            McpAnimations.animateHeight(detailsPanel, targetHeight, duration = 150) {
                revalidate()
            }
        } else {
            McpAnimations.animateHeight(detailsPanel, 0, duration = 150) {
                detailsPanel.isVisible = false
                revalidate()
            }
        }
    }

    private fun handleApproval(autoApprove: Boolean) {
        status = CompletionStatus.APPROVED
        statusLabel.text = "Approved${if (autoApprove) " (auto)" else ""}"
        isCompleted = true
        setupMainContent()
        onApprove(autoApprove)
    }

    private fun handleRejection() {
        status = CompletionStatus.REJECTED
        statusLabel.text = "Rejected"
        setupMainContent()
        onReject()
    }

    fun showExecutionStatus() {
        status = CompletionStatus.EXECUTING
        statusLabel.text = "Executing..."
        isCompleted = true
        setupMainContent()
    }

    fun showCompletionResult(result: String, isSuccess: Boolean) {
        status = if (isSuccess) CompletionStatus.COMPLETED else CompletionStatus.FAILED
        statusLabel.text = if (isSuccess) "Completed" else "Failed"
        isCompleted = true
        setupMainContent()
    }

    private fun setupKeyboardShortcuts() {
        if (isCompleted) return

        isFocusable = true

        val inputMap = getInputMap(WHEN_IN_FOCUSED_WINDOW)
        val actionMap = getActionMap()

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, 0), "approve")
        actionMap.put("approve", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (!isCompleted) handleApproval(false)
            }
        })

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0), "autoApprove")
        actionMap.put("autoApprove", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (!isCompleted) handleApproval(true)
            }
        })

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_N, 0), "reject")
        actionMap.put("reject", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (!isCompleted) handleRejection()
            }
        })

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggle")
        actionMap.put("toggle", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (!isCompleted && fullParameters != null && fullParameters.isNotEmpty()) {
                    toggleExpanded()
                }
            }
        })
    }
}
