package ee.carlrobert.codegpt.toolwindow.chat.editor.header

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.ui.*
import com.intellij.ui.components.JBLabel
import ee.carlrobert.codegpt.completions.CancellableRequest
import ee.carlrobert.codegpt.ui.IconActionButton
import javax.swing.BoxLayout
import javax.swing.JPanel

class LoadingPanel(
    initialText: String,
    private var request: CancellableRequest? = null,
    private val onCancel: (() -> Unit)? = null
) : JPanel() {

    private val loadingLabel = JBLabel(initialText, AnimatedIcon.Default(), JBLabel.LEFT)
    
    private val stopButton = IconActionButton(
        object : AnAction("Stop", "Stop the current operation", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) {
                request?.cancel()
                onCancel?.invoke()
            }
        },
        "stop-operation"
    ).apply {
        isVisible = request != null
    }
    
    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(loadingLabel)
        add(SeparatorComponent(
            ColorUtil.fromHex("#48494b"),
            SeparatorOrientation.VERTICAL
        ).apply {
            setVGap(4)
            setHGap(6)
        })
        add(stopButton)
    }
    
    fun setText(text: String) {
        loadingLabel.text = text
    }
    
    fun setRequest(request: CancellableRequest?) {
        this.request = request
        stopButton.isVisible = request != null
        revalidate()
        repaint()
    }
    
    fun showStopButton(show: Boolean) {
        stopButton.isVisible = show && request != null
    }
}
