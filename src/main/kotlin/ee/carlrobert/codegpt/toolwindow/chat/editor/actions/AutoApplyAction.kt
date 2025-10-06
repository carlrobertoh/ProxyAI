package ee.carlrobert.codegpt.toolwindow.chat.editor.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.intellij.ui.components.AnActionLink
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType.INCEPTION
import ee.carlrobert.codegpt.util.EditorUtil
import javax.swing.JComponent

class AutoApplyAction(
    private val project: Project,
    private val toolwindowEditor: EditorEx,
    private val filePath: String?,
    private val virtualFile: VirtualFile?,
    private val onApply: (AnActionLink) -> Unit,
) : CustomComponentAction, AnAction("Apply", "Apply changes to the editor", AllIcons.Actions.Execute) {

    private val anActionLink: AnActionLink = AnActionLink("Apply", this).apply {
        icon = AllIcons.Actions.Execute
        border = JBUI.Borders.empty(0, 4)
    }

    override fun actionPerformed(e: AnActionEvent) {
        onApply(anActionLink)
    }

    override fun update(e: AnActionEvent) {
        val autoApplyProvider =
            ModelSelectionService.getInstance().getServiceForFeature(FeatureType.AUTO_APPLY)
        if (autoApplyProvider == INCEPTION) {
            anActionLink.text = "Apply"
            anActionLink.isEnabled = false
            anActionLink.toolTipText = "Auto Apply is temporarily disabled for Inception provider"
            return
        }

        if (virtualFile != null) {
            anActionLink.text = "Apply"
            anActionLink.isEnabled = true
            anActionLink.toolTipText = "Apply changes to ${virtualFile.name}"

            if (virtualFile.readText().trim() == toolwindowEditor.document.text.trim()) {
                anActionLink.isEnabled = false
                anActionLink.isVisible = true
                anActionLink.toolTipText = "No changes to apply"
            }
            return
        }

        val selectedEditor = EditorUtil.getSelectedEditor(project)
        val selectedEditorFile = selectedEditor?.virtualFile
        val canApply = selectedEditorFile != null && selectedEditorFile.isWritable

        anActionLink.text = if (canApply) "Apply to ${selectedEditorFile.name}" else "Apply"
        anActionLink.isEnabled = canApply
        anActionLink.isVisible = canApply
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        return anActionLink
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}