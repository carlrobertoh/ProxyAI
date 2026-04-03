package ee.carlrobert.codegpt.toolwindow.chat.editor.header

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ee.carlrobert.codegpt.CodeGPTBundle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.Timer

class ErrorPopoverHandler(
    private val project: Project,
    private val errorLabel: JComponent,
    private val errorContent: String?
) {
    private var errorPopup: JBPopup? = null

    fun install() {
        for (listener in errorLabel.mouseListeners) {
            errorLabel.removeMouseListener(listener)
        }

        errorLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                errorLabel.putClientProperty("mouseInside", true)
                showErrorPopoverWithHover()
            }

            override fun mouseExited(e: MouseEvent) {
                errorLabel.putClientProperty("mouseInside", false)
                schedulePopupCloseIfNeeded()
            }
        })
    }

    private fun schedulePopupCloseIfNeeded() {
        Timer(100) {
            if (errorLabel.getClientProperty("mouseInside") != true &&
                errorLabel.getClientProperty("popupMouseInside") != true
            ) {
                errorPopup?.cancel()
                errorPopup = null
            }
        }.apply { isRepeats = false }.start()
    }

    private fun showErrorPopoverWithHover() {
        if (errorContent == null) return
        if (errorPopup?.isVisible == true) return

        val contentPane = JEditorPane("text/html", formatContent(errorContent)).apply {
            isEditable = false
            isOpaque = true
            border = JBUI.Borders.emptyTop(10)
            foreground = UIUtil.getToolTipForeground()
            background = UIUtil.getToolTipActionBackground()
            font = UIUtil.getToolTipFont()
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }
        val scrollPane = JBScrollPane(contentPane).apply {
            border = JBUI.Borders.empty()
            viewport.background = UIUtil.getToolTipActionBackground()
            background = UIUtil.getToolTipActionBackground()
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, null)
            .setRequestFocus(false)
            .setResizable(true)
            .setMovable(true)
            .setTitle(CodeGPTBundle.get("headerPanel.error.searchBlockNotMapped.title"))
            .setShowShadow(true)
            .setCancelOnClickOutside(true)
            .createPopup()

        val popupHoverListener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                errorLabel.putClientProperty("popupMouseInside", true)
            }

            override fun mouseExited(e: MouseEvent) {
                errorLabel.putClientProperty("popupMouseInside", false)
                schedulePopupCloseIfNeeded()
            }
        }
        contentPane.addMouseListener(popupHoverListener)
        scrollPane.addMouseListener(popupHoverListener)

        errorPopup = popup
        popup.showUnderneathOf(errorLabel)
    }

    private fun formatContent(content: String): String {
        return if (content.trimStart().startsWith("<html", ignoreCase = true)) {
            content
        } else {
            val escaped = buildString(content.length) {
                content.forEach { ch ->
                    append(
                        when (ch) {
                            '<' -> "&lt;"
                            '>' -> "&gt;"
                            '&' -> "&amp;"
                            '"' -> "&quot;"
                            else -> ch
                        }
                    )
                }
            }.replace("\n", "<br/>")

            "<html><body>$escaped</body></html>"
        }
    }
}
