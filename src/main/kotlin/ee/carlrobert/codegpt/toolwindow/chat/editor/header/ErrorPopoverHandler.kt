package ee.carlrobert.codegpt.toolwindow.chat.editor.header

import com.intellij.codeInsight.documentation.DocumentationHintEditorPane
import com.intellij.lang.documentation.DocumentationImageResolver
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ee.carlrobert.codegpt.CodeGPTBundle
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
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

        val documentationHint = DocumentationHintEditorPane(
            project,
            emptyMap(),
            object : DocumentationImageResolver {
                override fun resolveImage(url: String): Image? = null
            }
        ).apply {
            setText(errorContent)
            isEditable = false
            isOpaque = true
            border = JBUI.Borders.emptyTop(10)
            foreground = UIUtil.getToolTipForeground()
            background = UIUtil.getToolTipActionBackground()
            font = UIUtil.getToolTipFont()
        }

        val popup = PopupFactoryImpl.getInstance()
            .createComponentPopupBuilder(documentationHint, null)
            .setRequestFocus(false)
            .setResizable(true)
            .setMovable(true)
            .setTitle(CodeGPTBundle.get("headerPanel.error.searchBlockNotMapped.title"))
            .setShowShadow(true)
            .setCancelOnClickOutside(true)
            .createPopup()

        documentationHint.setHint(popup)

        documentationHint.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                errorLabel.putClientProperty("popupMouseInside", true)
            }

            override fun mouseExited(e: MouseEvent) {
                errorLabel.putClientProperty("popupMouseInside", false)
                schedulePopupCloseIfNeeded()
            }
        })

        errorPopup = popup
        popup.showUnderneathOf(errorLabel)
    }
}
