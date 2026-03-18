package ee.carlrobert.codegpt.toolwindow.ui

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent

object ModelListPopups {

    fun createPopup(
        group: DefaultActionGroup,
        context: DataContext,
        disposeCallback: Runnable?
    ): JBPopup {
        val popup = ModelListPopup(group, context)
        if (disposeCallback != null) {
            popup.addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    disposeCallback.run()
                }
            })
        }
        popup.isShowSubmenuOnHover = true
        return popup
    }
}
