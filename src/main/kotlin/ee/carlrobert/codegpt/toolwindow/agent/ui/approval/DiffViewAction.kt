package ee.carlrobert.codegpt.toolwindow.agent.ui.approval

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFileManager
import java.awt.Component

object DiffViewAction {
    
    fun showDiff(filePath: String, component: Component) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project != null) {
            showDiff(filePath, project)
        }
    }
    
    fun showDiff(filePath: String, project: Project) {
        val virtualFile = VirtualFileManager.getInstance().findFileByUrl("file://$filePath")
        if (virtualFile != null) {
            val contentFactory = DiffContentFactory.getInstance()
            val currentContent = contentFactory.create(project, virtualFile)
            val emptyContent = contentFactory.createEmpty()

            val diffRequest = SimpleDiffRequest(
                "Changes in ${virtualFile.name}",
                emptyContent,
                currentContent,
                "Before",
                "After"
            )

            DiffManager.getInstance().showDiff(project, diffRequest)
        }
    }

    fun showDiff(before: String, after: String, title: String, project: Project?) {
        val proj = project ?: ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val contentFactory = DiffContentFactory.getInstance()
        val left = contentFactory.create(before)
        val right = contentFactory.create(after)
        val diffRequest = SimpleDiffRequest(
            title,
            left,
            right,
            "Before",
            "After"
        )
        DiffManager.getInstance().showDiff(proj, diffRequest)
    }
}
