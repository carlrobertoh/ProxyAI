package ee.carlrobert.codegpt.toolwindow.chat.editor.header

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.ui.*
import com.intellij.ui.components.JBLabel
import ee.carlrobert.codegpt.ui.IconActionButton
import okhttp3.sse.EventSource
import javax.swing.BoxLayout
import javax.swing.JPanel

class LoadingPanel(
    initialText: String,
    private var eventSource: EventSource? = null,
    private val onCancel: (() -> Unit)? = null
) : JPanel() {

    private val loadingLabel = JBLabel(initialText, AnimatedIcon.Default(), JBLabel.LEFT)
    
    private val stopButton = IconActionButton(
        object : AnAction("Stop", "Stop the current operation", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) {
                eventSource?.cancel()
                onCancel?.invoke()
            }
        },
        "stop-operation"
    ).apply {
        isVisible = eventSource != null
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
    
    fun setEventSource(source: EventSource?) {
        eventSource = source
        stopButton.isVisible = source != null
        revalidate()
        repaint()
    }
    
    fun showStopButton(show: Boolean) {
        stopButton.isVisible = show && eventSource != null
    }
}