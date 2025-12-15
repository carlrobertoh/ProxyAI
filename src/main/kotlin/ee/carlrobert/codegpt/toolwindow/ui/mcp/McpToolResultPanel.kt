package ee.carlrobert.codegpt.toolwindow.ui.mcp

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Graphics
import javax.swing.*

class McpToolResultPanel(
    private val toolName: String,
    private val result: String,
    private val serverName: String = "",
    private val isError: Boolean = false
) : JBPanel<McpToolResultPanel>() {

    init {
        layout = BorderLayout()
        background = if (isError) {
            UIUtil.getPanelBackground()
        } else {
            UIUtil.getListBackground()
        }
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(4, 0),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    if (isError) JBColor.RED.darker() else JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
                    1
                ),
                JBUI.Borders.empty(8)
            )
        )

        setupUI()
    }

    private fun setupUI() {
        val headerPanel = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.LEFT, 0, 0)
            isOpaque = false

            add(JBLabel(if (isError) AllIcons.General.Error else null))
            add(Box.createHorizontalStrut(8))
            add(JBLabel("Tool: $toolName").apply {
                font = JBUI.Fonts.label().asBold()
                foreground = if (isError) JBColor.RED else JBUI.CurrentTheme.Label.foreground()
            })
        }

        add(headerPanel, BorderLayout.NORTH)

        val resultPreview = formatResultPreview(result)
        if (resultPreview.isNotEmpty()) {
            val previewPanel = JBPanel<JBPanel<*>>().apply {
                layout = BorderLayout()
                isOpaque = false
                border = JBUI.Borders.emptyTop(4)
            }

            val resultLabel = JBLabel(resultPreview).apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBUI.CurrentTheme.Label.disabledForeground()

                toolTipText = formatTooltip(result)
            }
            previewPanel.add(resultLabel, BorderLayout.CENTER)

            if (resultPreview.endsWith("...") || result.length > 150) {
                val showMoreLink = ActionLink("Show more") {
                    showFullContent()
                }.apply {
                    font = JBUI.Fonts.smallFont()
                    border = JBUI.Borders.emptyLeft(8)
                }
                previewPanel.add(showMoreLink, BorderLayout.EAST)
            }

            add(previewPanel, BorderLayout.CENTER)
        }

        toolTipText = formatTooltip(result)
    }

    private fun formatResultPreview(result: String): String {
        val cleaned = result
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("  ", " ")
            .trim()

        return when {
            isError -> {
                if (cleaned.length > 100) {
                    cleaned.take(100) + "..."
                } else {
                    cleaned
                }
            }

            cleaned.contains("search results") || cleaned.contains("results found") -> {
                val countMatch =
                    Regex("(\\d+)\\s*(?:search\\s*)?results?").find(cleaned.lowercase())
                if (countMatch != null) {
                    "Found ${countMatch.groupValues[1]} results"
                } else {
                    "Search completed"
                }
            }

            cleaned.contains("SELECT") || cleaned.contains("INSERT") || cleaned.contains("UPDATE") -> {
                val rowMatch = Regex("(\\d+)\\s*rows?").find(cleaned)
                if (rowMatch != null) {
                    "Query returned ${rowMatch.groupValues[1]} rows"
                } else {
                    "Query executed successfully"
                }
            }

            cleaned.length > 150 -> {
                cleaned.take(150) + "..."
            }

            else -> cleaned
        }
    }

    private fun formatTooltip(result: String): String {
        return "<html><body style='width: 500px; font-family: ${JBUI.Fonts.label().family}; font-size: ${JBUI.Fonts.label().size}px;'>" +
                "<b>Tool: $toolName</b><br><br>" +
                result.replace("\n", "<br>")
                    .replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;")
                    .replace(" ", "&nbsp;") +
                "</body></html>"
    }

    private fun showFullContent() {
        val dialog = object : DialogWrapper(false) {
            init {
                title = "Tool Result: $toolName"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val panel = BorderLayoutPanel().apply {
                    border = JBUI.Borders.empty()
                }

                val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(JLabel(if (isError) AllIcons.General.Error else null))
                    add(Box.createHorizontalStrut(8))
                    add(JLabel("Tool: $toolName").apply {
                        font = JBUI.Fonts.label().asBold()
                    })
                    add(JLabel(" • ").apply {
                        foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    })
                    add(JLabel(serverName).apply {
                        foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    })
                }
                panel.add(headerPanel, BorderLayout.NORTH)

                val textArea = JTextArea(result).apply {
                    isEditable = false
                    font = JBUI.Fonts.label()
                    lineWrap = true
                    wrapStyleWord = true
                    caretPosition = 0
                }

                val scrollPane = JBScrollPane(textArea).apply {
                    preferredSize = JBUI.size(600, 400)
                }
                panel.add(scrollPane, BorderLayout.CENTER)

                return panel
            }
        }

        dialog.show()
    }

    override fun paintComponent(g: Graphics) {
        paintWithAlpha(g) {
            super.paintComponent(g)
        }
    }
}
