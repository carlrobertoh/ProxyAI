package ee.carlrobert.codegpt.toolwindow.agent.ui.approval

import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.toolwindow.agent.ui.SimpleAgentApprovalPanel
import javax.swing.JPanel

interface ApprovalPanelFactory {
    fun create(
        project: Project,
        request: ToolApprovalRequest,
        onApprove: (autoApproveSession: Boolean) -> Unit,
        onReject: () -> Unit
    ): JPanel
}

object DefaultApprovalPanelFactory : ApprovalPanelFactory {
    override fun create(
        project: Project,
        request: ToolApprovalRequest,
        onApprove: (autoApproveSession: Boolean) -> Unit,
        onReject: () -> Unit
    ): JPanel = when (request.type) {
        ToolApprovalType.BASH -> BashApprovalPanel(project, request, onApprove, onReject)
        ToolApprovalType.WRITE -> WriteApprovalPanel(project, request, onApprove, onReject)
        ToolApprovalType.EDIT -> EditApprovalPanel(project, request, onApprove, onReject)
        ToolApprovalType.GENERIC -> SimpleAgentApprovalPanel(
            request.title,
            request.details,
            onApprove,
            onReject
        )
    }
}
