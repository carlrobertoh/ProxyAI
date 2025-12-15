package ee.carlrobert.codegpt.toolwindow.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatMessageResponseBody
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

open class ResponseMessagePanel : BaseMessagePanel() {

    private val originalContent = BorderLayoutPanel().apply {
        isOpaque = false
    }

    override fun createDisplayNameLabel(): JBLabel {
        return JBLabel(
            CodeGPTBundle.get("project.label"),
            Icons.Default,
            SwingConstants.LEADING
        )
            .setAllowAutoWrapping(true)
            .withFont(JBFont.label().asBold())
            .apply {
                iconTextGap = 6
            }
    }

    init {
        addContent(createContentWrapper())
    }

    private fun createContentWrapper(): JPanel {
        return BorderLayoutPanel().apply {
            isOpaque = false
            add(originalContent, BorderLayout.CENTER)
        }
    }

    fun setResponseContent(content: JComponent) {
        originalContent.removeAll()
        originalContent.add(content, BorderLayout.CENTER)
        originalContent.revalidate()
        originalContent.repaint()
    }

    fun getResponseComponent(): ChatMessageResponseBody? {
        return originalContent.components
            .firstOrNull { it is ChatMessageResponseBody } as? ChatMessageResponseBody
    }
}