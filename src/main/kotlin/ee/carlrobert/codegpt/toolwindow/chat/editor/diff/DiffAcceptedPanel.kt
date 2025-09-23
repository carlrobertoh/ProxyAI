package ee.carlrobert.codegpt.toolwindow.chat.editor.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffChange
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.writeText
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.toolwindow.chat.editor.state.DiffEditorState
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

class DiffAcceptedPanel(
    project: Project,
    virtualFile: VirtualFile,
    before: String,
    after: String,
    changes: List<UnifiedDiffChange>,
) : InlineBanner("", EditorNotificationPanel.Status.Success) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8)

        val fileLink = createFileLink(project, virtualFile.path, virtualFile.name)
        val statsPanel = DiffStatsComponent.createStatsPanel(changes)

        val contentPanel = BorderLayoutPanel().andTransparent()
            .addToLeft(createLeftPanel(fileLink, statsPanel))
            .addToRight(createRightPanel(project, before, after, virtualFile))

        add(contentPanel)
        showCloseButton(false)
    }

    private fun createFileLink(project: Project, filePath: String, name: String): ActionLink =
        ActionLink(name) {
            LocalFileSystem.getInstance().findFileByPath(filePath)?.let { vFile ->
                FileEditorManager.getInstance(project).openFile(vFile, true)
            }
        }.apply { toolTipText = filePath }

    private fun createLeftPanel(fileLink: ActionLink, statsPanel: JPanel): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(fileLink)
            add(statsPanel)
        }

    private fun createRightPanel(
        project: Project,
        before: String,
        after: String,
        virtualFile: VirtualFile
    ): JPanel {
        val revertChangesLink = ActionLink(CodeGPTBundle.get("diff.acceptedPanel.revertChanges")) {
            val contentFactory = DiffContentFactory.getInstance()
            val left = contentFactory.create(project, virtualFile)
            val right = contentFactory.create(project, before, virtualFile.fileType)

            val diffRequest = SimpleDiffRequest(
                CodeGPTBundle.get("diff.acceptedPanel.revertChanges"),
                left, right,
                CodeGPTBundle.get("diff.acceptedPanel.after"),
                CodeGPTBundle.get("diff.acceptedPanel.before")
            ).apply {
                val revertAllButton =
                    DiffEditorState.createContextActionButton("Revert All", AllIcons.Actions.Redo) {
                        runWriteAction {
                            virtualFile.writeText(StringUtil.convertLineSeparators(before))
                        }
                    }

                putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(revertAllButton))
            }

            DiffManager.getInstance().showDiff(project, diffRequest)
        }
        val viewDetailsLink = ActionLink(CodeGPTBundle.get("diff.acceptedPanel.viewDetails")) {
            val contentFactory = DiffContentFactory.getInstance()
            val left = contentFactory.create(project, before, virtualFile.fileType)
            val right = contentFactory.create(project, after, virtualFile.fileType)

            val diffRequest = SimpleDiffRequest(
                CodeGPTBundle.get("diff.acceptedPanel.viewDetails"),
                left, right,
                CodeGPTBundle.get("diff.acceptedPanel.before"),
                CodeGPTBundle.get("diff.acceptedPanel.after")
            )

            DiffManager.getInstance().showDiff(project, diffRequest)
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(revertChangesLink)
            add(Box.createHorizontalStrut(6))
            add(viewDetailsLink)
        }
    }
}
