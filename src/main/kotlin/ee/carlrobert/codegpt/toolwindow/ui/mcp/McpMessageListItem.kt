package ee.carlrobert.codegpt.toolwindow.ui.mcp

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.toolwindow.ui.BaseMessagePanel
import java.awt.Graphics
import javax.swing.JPanel
import javax.swing.SwingConstants

class McpMessageListItem(
    content: JPanel
) : BaseMessagePanel() {

    init {
        addContent(content)
        border = JBUI.Borders.empty()
    }

    override fun createDisplayNameLabel(): JBLabel {
        return JBLabel(
            "Tool",
            null,
            SwingConstants.LEADING
        ).apply {
            iconTextGap = 6
            font = JBUI.Fonts.label()
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }
    }

    companion object {
        fun fromToolPanel(toolPanel: CompactToolPanel): McpMessageListItem {
            return McpMessageListItem(toolPanel)
        }

        fun fromGroupPanel(groupPanel: ToolGroupPanel): McpMessageListItem {
            return McpMessageListItem(groupPanel)
        }
    }

    override fun paintComponent(g: Graphics) {
        paintWithAlpha(g) {
            super.paintComponent(g)
        }
    }
}
