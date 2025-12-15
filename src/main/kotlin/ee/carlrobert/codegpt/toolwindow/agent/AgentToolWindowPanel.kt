package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.toolwindow.agent.ui.AgentCreditsToolbarLabel
import javax.swing.JComponent

class AgentToolWindowPanel(
    private val project: Project
) : SimpleToolWindowPanel(true), Disposable {

    private val contentManager = project.service<AgentToolWindowContentManager>()
    private val tabbedPane = contentManager.initializeTabbedPane()

    init {
        toolbar = createToolbar()
        setContent(tabbedPane)
    }

    private fun createToolbar(): JComponent {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction(
                "New Agent",
                "Create a new Agent session",
                AllIcons.General.Add
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    contentManager.createNewAgentTab()
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }
            })

            add(object : AnAction(
                "Reset Current Agent",
                "Clear all messages and reset the current agent view",
                AllIcons.General.Reset
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    contentManager.resetCurrentlyActiveTab()
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }
            })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("AgentToolWindow", actionGroup, true)
        toolbar.targetComponent = tabbedPane
        val creditsLabel = AgentCreditsToolbarLabel(project)
        Disposer.register(this, creditsLabel)
        return BorderLayoutPanel()
            .addToLeft(toolbar.component)
            .addToRight(creditsLabel)
    }

    fun getTabbedPane(): AgentToolWindowTabbedPane = tabbedPane

    override fun dispose() {
        tabbedPane.dispose()
    }
}
