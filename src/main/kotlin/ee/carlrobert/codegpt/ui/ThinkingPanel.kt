package ee.carlrobert.codegpt.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.util.MarkdownUtil
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import javax.swing.*

class ThinkingPanel : BorderLayoutPanel() {

    private var finished: Boolean = false
    private val startedAtMillis = System.currentTimeMillis()
    private val responseBodyContent = UIUtil.createTextPane("", false).apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }
    private val contentPanel = createContentPanel()
    private val toggleButton: JToggleButton = createToggleButton()

    init {
        isOpaque = false

        add(toggleButton, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun isFinished(): Boolean = finished

    fun setFinished() {
        if (finished) return

        toggleButton.text = formatFinishedTitle()
        toggleButton.isSelected = false
        finished = true

        contentPanel.add(Box.createVerticalStrut(8))
        contentPanel.add(
            BorderLayoutPanel().withBorder(
                JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                    JBUI.Borders.empty(8, 0)
                )
            )
        )
        revalidate()
        repaint()
    }

    fun updateText(text: String) {
        responseBodyContent.text = MarkdownUtil.convertMdToHtml(text)
    }

    fun getTitleText(): String = toggleButton.text

    fun getRenderedText(): String = responseBodyContent.text

    private fun createContentPanel(): JPanel {
        val panel = JPanel().apply {
            isOpaque = false
            isVisible = true
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0, 0)
        }

        panel.add(responseBodyContent)
        panel.add(Box.createVerticalStrut(4))
        return panel
    }

    private fun createToggleButton() =
        JToggleButton(
            CodeGPTBundle.get("thoughtProcess.thinking"),
            AllIcons.General.ArrowRight,
            true
        ).apply {
            isFocusPainted = false
            isContentAreaFilled = false
            background = background
            selectedIcon = AllIcons.General.ArrowDown
            border = null
            horizontalAlignment = SwingConstants.LEFT
            horizontalTextPosition = SwingConstants.RIGHT
            iconTextGap = 4
            addItemListener { e: ItemEvent ->
                contentPanel.isVisible = e.stateChange == ItemEvent.SELECTED
            }
        }

    private fun formatFinishedTitle(): String {
        val elapsedSeconds = ((System.currentTimeMillis() - startedAtMillis) / 1000).toInt()
            .coerceAtLeast(1)
        return if (elapsedSeconds < 60) {
            CodeGPTBundle.get("thoughtProcess.duration.seconds", elapsedSeconds)
        } else {
            CodeGPTBundle.get("thoughtProcess.duration.minutes", elapsedSeconds / 60)
        }
    }
}
