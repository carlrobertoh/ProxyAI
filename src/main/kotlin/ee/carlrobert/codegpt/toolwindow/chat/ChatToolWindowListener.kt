package ee.carlrobert.codegpt.toolwindow.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

class ChatToolWindowListener : ToolWindowManagerListener {

    override fun toolWindowShown(toolWindow: ToolWindow) {
        if ("ProxyAI" == toolWindow.id) {
            requestFocusForTextArea(toolWindow.project)
        }
    }

    private fun requestFocusForTextArea(project: Project) {
        val contentManager = project.getService(ChatToolWindowContentManager::class.java)
        contentManager.tryFindChatToolWindowPanel().ifPresent { panel ->
            panel.requestFocusForInput()
        }
    }
}
