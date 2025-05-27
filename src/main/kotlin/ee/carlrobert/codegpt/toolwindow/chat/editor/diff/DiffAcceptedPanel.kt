package ee.carlrobert.codegpt.toolwindow.chat.editor.diff

import com.intellij.diff.tools.fragmented.UnifiedDiffChange
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JPanel

class DiffAcceptedPanel(
    project: Project,
    changes: List<UnifiedDiffChange>,
    filePath: String,
    onViewDetails: () -> Unit,
) : InlineBanner() {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8)

        val name = File(filePath).name
        val fileLink = createFileLink(project, filePath, name)
        val statsPanel = DiffStatsComponent.createStatsPanel(changes)

        val contentPanel = BorderLayoutPanel().andTransparent()
            .addToLeft(createLeftPanel(fileLink, statsPanel))
            .addToRight(ActionLink("View Details") { onViewDetails() })

        add(contentPanel)
        status = EditorNotificationPanel.Status.Success
        showCloseButton(false)
    }

    private fun createFileLink(project: Project, filePath: String, name: String): ActionLink {
        return ActionLink(name) {
            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            if (vFile != null) {
                FileEditorManager.getInstance(project).openFile(vFile, true)
            }
        }
    }

    private fun createLeftPanel(fileLink: ActionLink, statsPanel: JPanel): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(fileLink)
            add(statsPanel)
        }
    }
}