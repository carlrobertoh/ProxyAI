package ee.carlrobert.codegpt.toolwindow.agent.ui.approval

sealed interface ToolApprovalPayload

data class BashPayload(
    val command: String,
    val description: String?
) : ToolApprovalPayload

data class WritePayload(
    val filePath: String,
    val content: String
) : ToolApprovalPayload

data class EditPayload(
    val filePath: String,
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean,
    val proposedContent: String? = null
) : ToolApprovalPayload

data class ProxyAIEditPayload(
    val filePath: String,
    val updateSnippet: String,
    val originalContent: String,
    val updatedContent: String
) : ToolApprovalPayload