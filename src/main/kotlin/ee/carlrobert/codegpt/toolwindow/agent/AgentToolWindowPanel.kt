package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.toolwindow.ToolWindowInitialState
import ee.carlrobert.codegpt.toolwindow.agent.ui.AgentCreditsToolbarLabel
import java.awt.CardLayout
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel

class AgentToolWindowPanel(
    private val project: Project
) : SimpleToolWindowPanel(true), Disposable {

    companion object {
        private const val LANDING_CARD = "LANDING"
        private const val TABS_CARD = "TABS"
    }

    private val contentManager = project.service<AgentToolWindowContentManager>()
    private val tabbedPane = contentManager.initializeTabbedPane()
    private val centerLayout = CardLayout()
    private val centerPanel = JPanel(centerLayout)
    private val creditsLabel = AgentCreditsToolbarLabel(project, ::currentSession)
    private var landingPanel: AgentToolWindowTabPanel? = null

    init {
        tabbedPane.setTabLifecycleCallbacks(
            onTabsOpened = { showTabsView() },
            onAllTabsClosed = { showLandingView() }
        )
        centerPanel.add(tabbedPane, TABS_CARD)
        toolbar = createToolbar()
        setContent(centerPanel)
        showLandingView()
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
        Disposer.register(this, creditsLabel)
        return BorderLayoutPanel()
            .addToLeft(toolbar.component)
            .addToRight(creditsLabel)
    }

    fun getTabbedPane(): AgentToolWindowTabbedPane = tabbedPane

    private fun showTabsView() {
        disposeLandingPanel()
        centerLayout.show(centerPanel, TABS_CARD)
        creditsLabel.refresh()
    }

    private fun showLandingView() {
        disposeLandingPanel()
        val panel = createLandingPanel()
        landingPanel = panel
        centerPanel.add(panel, LANDING_CARD)
        centerLayout.show(centerPanel, LANDING_CARD)
        panel.requestFocusForTextArea()
        centerPanel.revalidate()
        centerPanel.repaint()
        creditsLabel.refresh()
    }

    private fun createLandingPanel(): AgentToolWindowTabPanel {
        val draftSession = AgentSession(
            sessionId = UUID.randomUUID().toString(),
            conversation = Conversation()
        )
        return AgentToolWindowTabPanel(
            project = project,
            agentSession = draftSession,
            initialMessageSubmitHandler = { message ->
                val initialState = ToolWindowInitialState(
                    conversation = draftSession.conversation,
                    tags = message.tags
                )
                disposeLandingPanel()
                val panel = contentManager.createNewAgentTab(draftSession)
                panel.restoreDraftState(initialState)
                panel.submitMessage(message)
            }
        )
    }

    private fun disposeLandingPanel() {
        val current = landingPanel ?: return
        centerPanel.remove(current)
        Disposer.dispose(current)
        landingPanel = null
    }

    private fun currentSession(): AgentSession? {
        return landingPanel?.getAgentSession()
            ?: contentManager.getActiveTabPanel()?.getAgentSession()
    }

    override fun dispose() {
        tabbedPane.setTabLifecycleCallbacks(onTabsOpened = {}, onAllTabsClosed = {})
        disposeLandingPanel()
        tabbedPane.dispose()
    }
}
