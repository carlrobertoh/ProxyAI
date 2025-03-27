package ee.carlrobert.codegpt.actions.toolwindow

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager

class ClearChatTagsAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project: Project = event.project ?: return
        project.getService(ChatToolWindowContentManager::class.java).clearAllTags()
    }
}