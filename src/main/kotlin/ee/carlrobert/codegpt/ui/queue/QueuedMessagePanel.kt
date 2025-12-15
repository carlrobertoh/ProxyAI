package ee.carlrobert.codegpt.ui.queue

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.conversations.message.QueuedMessage
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BorderFactory

class QueuedMessagePanel(
    private val queuedMessages: List<QueuedMessage>
) : JBPanel<QueuedMessagePanel>() {

    init {
        layout = BorderLayout()
        background = JBUI.CurrentTheme.ToolWindow.background()
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(2, 0),
            BorderFactory.createLineBorder(
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
                1
            )
        )
        setupUI()
    }

    private fun setupUI() {
        val contentPanel = JBPanel<JBPanel<*>>().apply {
            layout = BorderLayout()
            isOpaque = false
            border = JBUI.Borders.empty(8, 12)
        }

        val messagesPanel = JBPanel<JBPanel<*>>().apply {
            layout = BorderLayout()
            isOpaque = false
        }

        val count = queuedMessages.size
        val labelText = if (count == 1) {
            "QUEUED:"
        } else {
            "QUEUED ($count):"
        }

        val label = JBLabel(labelText).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = JBColor(0x808080, 0x808080)
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }

        messagesPanel.add(label, BorderLayout.NORTH)

        val messagesContentPanel = JBPanel<JBPanel<*>>().apply {
            layout = java.awt.GridBagLayout()
            isOpaque = false
        }

        for ((index, message) in queuedMessages.withIndex()) {
            val messageLabel = JBLabel("↳ ${message.prompt}").apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor(0x808080, 0x808080)
                border = JBUI.Borders.empty(2, 0)
            }

            val gbc = java.awt.GridBagConstraints().apply {
                gridx = 0
                gridy = index
                weightx = 1.0
                weighty = 0.0
                fill = java.awt.GridBagConstraints.HORIZONTAL
                anchor = java.awt.GridBagConstraints.NORTHWEST
                insets = java.awt.Insets(0, 0, 2, 0)
            }
            messagesContentPanel.add(messageLabel, gbc)
        }

        val scrollPane = JBScrollPane(messagesContentPanel).apply {
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
            isOpaque = false
            viewport.isOpaque = false
        }

        messagesPanel.add(scrollPane, BorderLayout.CENTER)
        contentPanel.add(messagesPanel, BorderLayout.CENTER)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun getQueuedMessages(): List<QueuedMessage> = queuedMessages
}