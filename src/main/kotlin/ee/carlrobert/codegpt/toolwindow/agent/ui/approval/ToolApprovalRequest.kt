package ee.carlrobert.codegpt.toolwindow.agent.ui.approval

data class ToolApprovalRequest(
    val type: ToolApprovalType,
    val title: String,
    val details: String,
    val payload: ToolApprovalPayload? = null
)
