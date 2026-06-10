package ee.carlrobert.codegpt.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import ee.carlrobert.codegpt.toolwindow.agent.AgentToolWindowPanel

class FocusAgentInputAction : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ProxyAI") ?: return
        toolWindow.show {
            val contentManager = toolWindow.contentManager
            val agentContent = contentManager.contents.firstOrNull { it.tabName == "Agent" }
                ?: contentManager.getContent(0)
                ?: return@show
            contentManager.setSelectedContent(agentContent)
            (agentContent.component as? AgentToolWindowPanel)?.focusActiveInput()
        }
    }
}
