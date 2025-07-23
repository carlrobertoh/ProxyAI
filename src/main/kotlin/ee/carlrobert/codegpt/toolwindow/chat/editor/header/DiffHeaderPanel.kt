package ee.carlrobert.codegpt.toolwindow.chat.editor.header

import com.intellij.diff.tools.fragmented.UnifiedDiffChange
import com.intellij.icons.AllIcons
import com.intellij.icons.AllIcons.Actions.Close
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runInEdt
import com.intellij.ui.components.ActionLink
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.diff.DiffAcceptedPanel
import ee.carlrobert.codegpt.ui.IconActionButton
import okhttp3.sse.EventSource
import java.awt.BorderLayout
import javax.swing.*

interface DiffHeaderActions {
    fun onAcceptAll()
    fun onOpenDiff()
    fun onClose()
}

class DiffHeaderPanel(
    config: HeaderConfig,
    retry: Boolean,
    private val actions: DiffHeaderActions,
    private var eventSource: EventSource? = null
) : HeaderPanel(config) {

    private val loadingPanel = LoadingPanel(
        when {
            retry -> CodeGPTBundle.get("toolwindow.chat.editor.diff.retrying")
            eventSource != null -> CodeGPTBundle.get("toolwindow.chat.editor.diff.applying")
            else -> CodeGPTBundle.get("toolwindow.chat.editor.diff.editing")
        },
        eventSource
    ) {
        handleDone()
    }

    private val actionLinksPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isVisible = false
        add(ActionLink("View Diff") { actions.onOpenDiff() })
        add(Box.createHorizontalStrut(6))
        add(ActionLink(CodeGPTBundle.get("shared.acceptAll")) { actions.onAcceptAll() })
        add(separator())
        add(IconActionButton(
            object : AnAction("Close", "Close the diff view", Close) {
                override fun actionPerformed(e: AnActionEvent) {
                    actions.onClose()
                }
            },
            "close-diff"
        ))
    }

    init {
        setupUI()
        runInEdt {
            loadingPanel.isVisible = true
            loadingPanel.setEventSource(eventSource)
        }
    }

    override fun initializeRightPanel(rightPanel: JPanel) {
        if (config.readOnly) return

        rightPanel.apply {
            add(actionLinksPanel)
            add(loadingPanel)
        }
    }

    fun handleDone() {
        eventSource = null
        runInEdt {
            actionLinksPanel.isVisible = true
            loadingPanel.isVisible = false
            revalidate()
            repaint()
        }
    }

    fun handleChangesApplied(before: String, after: String, patches: List<UnifiedDiffChange>) {
        eventSource = null
        actionLinksPanel.isVisible = false
        loadingPanel.isVisible = false

        virtualFile?.let {
            val diffAcceptedPanel = DiffAcceptedPanel(config.project, it, before, after, patches)
            runInEdt {
                val container = config.editorEx.component.parent
                if (container is ResponseEditorPanel) {
                    container.removeAll()
                    container.add(diffAcceptedPanel, BorderLayout.CENTER)
                    container.revalidate()
                    container.repaint()
                }
            }
        }
    }

    fun editing() {
        runInEdt {
            loadingPanel.setText(CodeGPTBundle.get("toolwindow.chat.editor.diff.editing"))
            loadingPanel.isVisible = true
            loadingPanel.showStopButton(eventSource != null)
        }
    }
}
