package ee.carlrobert.codegpt.toolwindow.ui.mcp

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import ee.carlrobert.llm.client.openai.completion.response.ToolCall
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Graphics
import javax.swing.BorderFactory
import javax.swing.JPanel

/**
 * Modern approval panel following JetBrains UI/UX guidelines.
 * Features a clean card-like design with proper spacing and visual hierarchy.
 */
class McpApprovalPanel(
    private val toolCall: ToolCall,
    private val serverName: String,
    private val onApprove: (autoApprove: Boolean) -> Unit,
    private val onReject: () -> Unit,
) : JBPanel<McpApprovalPanel>() {

    private var isCompleted = false
    private val fullParameters = McpParameterPreview.formatDetailed(toolCall.function.arguments)

    init {
        layout = BorderLayout()
        background = JBUI.CurrentTheme.ToolWindow.background()
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(2, 0),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
                    1
                ),
                JBUI.Borders.empty(1)
            )
        )

        setupUI()

        toolTipText = createParametersTooltip()
    }

    private fun setupUI() {
        val contentPanel = JBPanel<JBPanel<*>>().apply {
            layout = BorderLayout()
            isOpaque = false
            border = JBUI.Borders.empty(8, 12)
        }

        contentPanel.add(createHeaderSection(), BorderLayout.NORTH)
        contentPanel.add(createActionsSection(), BorderLayout.CENTER)

        add(contentPanel, BorderLayout.CENTER)
    }

    private fun createHeaderSection(): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            layout = BorderLayout()
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)

            val approvalLabel = JBLabel("Allow ProxyAI to execute this tool?").apply {
                font = JBUI.Fonts.label()
                foreground = JBUI.CurrentTheme.Label.foreground()
            }
            add(approvalLabel, BorderLayout.NORTH)

            val toolInfoPanel = JBPanel<JBPanel<*>>().apply {
                layout = FlowLayout(FlowLayout.LEFT, 0, 0)
                isOpaque = false
                border = JBUI.Borders.emptyTop(4)
            }

            toolInfoPanel.add(JBLabel(toolCall.function.name).apply {
                font = JBUI.Fonts.label().asBold()
                toolTipText = createParametersTooltip()
            })

            toolInfoPanel.add(JBLabel(" • ").apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })

            toolInfoPanel.add(JBLabel(serverName).apply {
                font = JBUI.Fonts.label()
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })

            add(toolInfoPanel, BorderLayout.CENTER)
        }
    }


    private fun createActionsSection(): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            layout = BorderLayout()
            isOpaque = false

            if (fullParameters != null && fullParameters.isNotEmpty()) {
                val paramsPanel = JBPanel<JBPanel<*>>().apply {
                    layout = BorderLayout()
                    isOpaque = false
                    border = JBUI.Borders.emptyBottom(8)
                }

                val paramsText = fullParameters.entries.joinToString("\n") { (key, value) ->
                    formatParameterEntry(key, value)
                }

                paramsPanel.add(createWrappedParamsArea(paramsText), BorderLayout.CENTER)
                add(paramsPanel, BorderLayout.NORTH)
            }

            val linksPanel = JBPanel<JBPanel<*>>().apply {
                layout = FlowLayout(FlowLayout.LEFT, 0, 0)
                isOpaque = false
            }

            linksPanel.add(ActionLink("Yes") {
                handleApproval(false)
            })

            linksPanel.add(JBLabel(" | ").apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })

            linksPanel.add(ActionLink("Always accept for this session") {
                handleApproval(true)
            })

            linksPanel.add(JBLabel(" | ").apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })

            linksPanel.add(ActionLink("No") {
                handleRejection()
            })

            add(linksPanel, BorderLayout.CENTER)
        }
    }

    private fun handleApproval(autoApprove: Boolean) {
        isCompleted = true
        isVisible = false
        parent?.remove(this)
        parent?.revalidate()
        parent?.repaint()
        onApprove(autoApprove)
    }

    private fun handleRejection() {
        isCompleted = true
        transformToCompactStatus("✗ Rejected", false)
        onReject()
    }

    private fun transformToCompactStatus(statusText: String, success: Boolean) {
        removeAll()

        layout = FlowLayout(FlowLayout.LEFT, 0, 0)
        border = JBUI.Borders.empty(8, 12)
        background = if (success) {
            JBUI.CurrentTheme.NotificationInfo.backgroundColor()
        } else {
            JBUI.CurrentTheme.NotificationError.backgroundColor()
        }

        // Removed MCP icon
        add(JBLabel(statusText).apply {
            foreground = if (success) {
                JBUI.CurrentTheme.NotificationInfo.foregroundColor()
            } else {
                JBUI.CurrentTheme.NotificationError.foregroundColor()
            }
        })

        revalidate()
        repaint()
    }

    private fun createParametersTooltip(): String {
        val params = fullParameters ?: return toolCall.function.name

        val paramHtml = if (params.isNotEmpty()) {
            params.entries.joinToString("<br>") { (key, value) ->
                "<b>$key:</b> ${value.toString().replace("\n", "<br>").replace(" ", "&nbsp;")}"
            }
        } else {
            "<i>No parameters</i>"
        }

        return "<html><body style='width: 500px; font-family: ${JBUI.Fonts.label().family}; font-size: ${JBUI.Fonts.label().size}px;'>" +
                "<b>Tool: ${toolCall.function.name}</b><br>" +
                "<b>Server: $serverName</b><br><br>" +
                "<b>Parameters:</b><br>" +
                paramHtml +
                "</body></html>"
    }

    override fun paintComponent(g: Graphics) {
        paintWithAlpha(g) {
            super.paintComponent(g)
        }
    }

    private fun formatParameterEntry(key: String, value: String): String {
        return "$key: ${value.replace("\n", "\n  ")}"
    }

    private fun createWrappedParamsArea(text: String): JBTextArea {
        return JBTextArea(text).apply {
            isEditable = false
            isFocusable = false
            lineWrap = true
            wrapStyleWord = false
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.Label.foreground()
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
                    1
                ),
                JBUI.Borders.empty(4, 8)
            )
            background = JBUI.CurrentTheme.CustomFrameDecorations.paneBackground()
            isOpaque = true
        }
    }
}
