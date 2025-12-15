package ee.carlrobert.codegpt.toolwindow.agent.ui

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.Icons
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Box

class SimpleAgentApprovalPanel(
    title: String,
    details: String?,
    private val onApprove: (autoApproveSession: Boolean) -> Unit,
    private val onReject: () -> Unit,
) : JBPanel<SimpleAgentApprovalPanel>() {

    init {
        layout = BorderLayout()
        isOpaque = false
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

        val content = JBPanel<JBPanel<*>>().apply {
            layout = BorderLayout()
            isOpaque = false
            border = JBUI.Borders.empty(8, 12)
        }

        val header = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.LEFT, 0, 0)
            isOpaque = false
        }
        header.add(JBLabel(Icons.MCP))
        header.add(Box.createHorizontalStrut(8))
        header.add(JBLabel(title).apply {
            font = JBUI.Fonts.label().asBold()
        })
        content.add(header, BorderLayout.NORTH)

        details?.takeIf { it.isNotBlank() }?.let { txt ->
            val detailsPanel = JBPanel<JBPanel<*>>().apply {
                layout = BorderLayout()
                isOpaque = false
                border = JBUI.Borders.empty(6, 0, 8, 0)
            }
            detailsPanel.add(JBLabel(txt).apply {
                font = JBUI.Fonts.smallFont()
            }, BorderLayout.CENTER)
            content.add(detailsPanel, BorderLayout.CENTER)
        }

        val actions = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.LEFT, 0, 0)
            isOpaque = false
        }
        actions.add(ActionLink("Accept") { approve(false) })
        actions.add(JBLabel("|").apply { foreground = JBUI.CurrentTheme.Label.disabledForeground() })
        actions.add(ActionLink("Always accept for this session") { approve(true) })
        actions.add(JBLabel(" | ").apply { foreground = JBUI.CurrentTheme.Label.disabledForeground() })
        actions.add(ActionLink("Reject") { reject() })
        content.add(actions, BorderLayout.SOUTH)

        add(content, BorderLayout.CENTER)
    }

    private fun approve(auto: Boolean) {
        onApprove(auto)
        removeSelf()
    }

    private fun reject() {
        onReject()
        removeSelf()
    }

    private fun removeSelf() {
        isVisible = false
        parent?.remove(this)
        parent?.revalidate()
        parent?.repaint()
    }
}
