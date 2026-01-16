package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

@Service(Service.Level.PROJECT)
class AgentToolWindowContentManager(private val project: Project) : Disposable {

    private val activeSessions = mutableMapOf<String, AgentSession>()
    private val tabPanels = mutableMapOf<String, AgentToolWindowTabPanel>()
    private val autoApprovedSessions = mutableSetOf<String>()
    private val sessionState = project.service<AgentSessionState>()
    private val tabbedPane = AgentToolWindowTabbedPane(project)

    fun initializeTabbedPane(): AgentToolWindowTabbedPane {
        project.messageBus.connect()
            .subscribe(AgentTabTitleNotifier.AGENT_TAB_TITLE_TOPIC, object : AgentTabTitleNotifier {
                override fun updateTabTitle(sessionId: String, title: String) {
                    renameTab(sessionId, title)
                }
            })

        createNewAgentTab()

        return tabbedPane
    }

    fun createNewAgentTab(
        sessionId: String? = null,
        select: Boolean = true
    ): AgentToolWindowTabPanel {
        val tabPanel = if (sessionId != null) {
            AgentToolWindowTabPanel(project, sessionId)
        } else {
            AgentToolWindowTabPanel(project)
        }
        val resolvedSessionId = tabPanel.getSessionId()
        val session = AgentSession(resolvedSessionId, tabPanel.getConversation())
        val sessionEntry = sessionState.ensureSession(resolvedSessionId)
        if (sessionEntry.displayName.isNotBlank()) {
            tabPanel.getAgentSession().displayName = sessionEntry.displayName
            session.displayName = sessionEntry.displayName
        }
        activeSessions[session.sessionId] = session
        tabPanels[session.sessionId] = tabPanel
        tabbedPane.addNewTab(tabPanel, select)

        return tabPanel
    }

    fun getActiveTabPanel(): AgentToolWindowTabPanel? {
        return tabbedPane.tryFindActiveTabPanel().orElse(null)
    }

    fun getTabbedPane(): AgentToolWindowTabbedPane = tabbedPane

    fun resetCurrentlyActiveTab() {
        tabbedPane.resetCurrentlyActiveTabPanel()
    }

    fun renameTab(sessionId: String, newName: String) {
        tabbedPane.tryFindTabTitle(sessionId).ifPresent { title ->
            val tabIndex = tabbedPane.indexOfTab(title)
            if (tabIndex >= 0) {
                tabbedPane.renameTab(tabIndex, newName)
            }
        }
    }

    fun setTabStatus(sessionId: String, status: AgentToolWindowTabbedPane.TabStatus) {
        tabbedPane.updateStatusForSession(sessionId, status)
    }

    fun markSessionAsAutoApproved(sessionId: String) {
        autoApprovedSessions.add(sessionId)
    }

    fun isSessionAutoApproved(sessionId: String): Boolean {
        return autoApprovedSessions.contains(sessionId)
    }

    companion object {
        fun getInstance(project: Project): AgentToolWindowContentManager {
            return project.service()
        }
    }

    override fun dispose() {
        tabPanels.values.forEach { tabPanel ->
            Disposer.dispose(tabPanel)
        }
        tabPanels.clear()
        activeSessions.clear()
    }
}
