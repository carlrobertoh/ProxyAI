package ee.carlrobert.codegpt.toolwindow.chat.editor.header

import com.intellij.diff.tools.fragmented.UnifiedDiffChange
import com.intellij.openapi.application.runInEdt
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.diff.DiffAcceptedPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.diff.DiffStatsComponent
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

interface DiffHeaderActions {
    fun onAcceptAll()
    fun onOpenDiff()
}

class DiffHeaderPanel(
    config: HeaderConfig,
    retry: Boolean,
    private val actions: DiffHeaderActions
) : HeaderPanel(config) {

    private val loadingLabel: JBLabel = JBLabel(
        if (retry) CodeGPTBundle.get("toolwindow.chat.editor.diff.retrying")
        else CodeGPTBundle.get("toolwindow.chat.editor.diff.reading"),
        AnimatedIcon.Default(),
        JBLabel.LEFT
    )
    private val actionLinksPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isVisible = false
        add(ActionLink("View Diff") { actions.onOpenDiff() })
        add(Box.createHorizontalStrut(4))
        add(JBLabel("·").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor.GRAY
        })
        add(Box.createHorizontalStrut(4))
        add(ActionLink(CodeGPTBundle.get("shared.acceptAll")) { actions.onAcceptAll() })
    }
    private val statsComponent: SimpleColoredComponent = SimpleColoredComponent()

    init {
        setupUI()
    }

    override fun initializeRightPanel(rightPanel: JPanel) {
        if (config.readOnly) return

        rightPanel.apply {
            add(statsComponent)
            add(separator())
            add(actionLinksPanel)
            add(loadingLabel)
        }
    }

    fun handleDone() {
        runInEdt {
            actionLinksPanel.isVisible = true
            loadingLabel.isVisible = false
            revalidate()
            repaint()
        }
    }

    fun handleChangesApplied(
        patches: List<UnifiedDiffChange>
    ) {
        actionLinksPanel.isVisible = false
        loadingLabel.isVisible = false

        val diffAcceptedPanel = DiffAcceptedPanel(config.project, patches, config.filePath!!) { }
        runInEdt {
            val container = config.editorEx.component.parent
            if (container is ResponseEditorPanel) {
                container.removeEditorAndAuxiliaryPanels()
                container.add(diffAcceptedPanel, BorderLayout.CENTER)
                container.revalidate()
                container.repaint()
            } else {
                setRightPanelComponent(diffAcceptedPanel)
                revalidate()
                repaint()
            }
        }
    }

    fun updateDiffStats(changes: List<UnifiedDiffChange>) {
        runInEdt {
            DiffStatsComponent.updateStatsComponent(statsComponent, changes)
            revalidate()
            repaint()
        }
    }

    fun editing() {
        runInEdt {
            loadingLabel.text = CodeGPTBundle.get("toolwindow.chat.editor.diff.editing")
            loadingLabel.isVisible = true
        }
    }
}
