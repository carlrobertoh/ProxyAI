package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.Tool
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalRequest
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalType
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ProxyAIEditPayload
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.file.FileUtil

class ConfirmingWriteTool(
    private val delegate: WriteTool,
    private val approve: suspend (name: String, details: String) -> Boolean
) : Tool<WriteTool.Args, WriteTool.Result>(
    argsSerializer = WriteTool.Args.serializer(),
    resultSerializer = WriteTool.Result.serializer(),
    name = delegate.name,
    description = delegate.descriptor.description
) {

    override suspend fun execute(args: WriteTool.Args): WriteTool.Result {
        val details = buildString {
            append("Path: ")
            append(args.filePath)
            append("\n")
            append("Bytes: ")
            append(args.content.toByteArray().size)
        }
        val ok = approve("Write", details)
        if (!ok) {
            return WriteTool.Result.Error(
                filePath = args.filePath,
                error = "User rejected write operation"
            )
        }
        return delegate.execute(args)
    }
}

class ConfirmingEditTool(
    private val delegate: EditTool,
    private val approve: suspend (name: String, details: String) -> Boolean
) : Tool<EditTool.Args, EditTool.Result>(
    argsSerializer = EditTool.Args.serializer(),
    resultSerializer = EditTool.Result.serializer(),
    name = delegate.name,
    description = delegate.descriptor.description
) {

    override suspend fun execute(args: EditTool.Args): EditTool.Result {
        val ok = approve("Edit", args.shortDescription)
        if (!ok) {
            return EditTool.Result.Error(
                filePath = args.filePath,
                error = "User rejected edit operation"
            )
        }
        return delegate.execute(args)
    }
}

class ConfirmingProxyAIEditTool(
    private val delegate: ProxyAIEditTool,
    private val project: Project,
    private val approve: suspend (request: ToolApprovalRequest) -> Boolean
) : Tool<ProxyAIEditTool.Args, ProxyAIEditTool.Result>(
    argsSerializer = ProxyAIEditTool.Args.serializer(),
    resultSerializer = ProxyAIEditTool.Result.serializer(),
    name = delegate.name,
    description = delegate.descriptor.description
) {

    override suspend fun execute(args: ProxyAIEditTool.Args): ProxyAIEditTool.Result {
        val result = delegate.execute(args)
        
        if (result !is ProxyAIEditTool.Result.Success) {
            return result
        }
        
        val payload = ProxyAIEditPayload(
            filePath = result.filePath,
            updateSnippet = args.updateSnippet,
            originalContent = result.originalCode,
            updatedContent = result.updatedCode
        )
        
        val approved = approve(
            ToolApprovalRequest(
                ToolApprovalType.EDIT,
                "Allow Edit?",
                args.shortDescription,
                payload
            )
        )
        
        if (!approved) {
            return ProxyAIEditTool.Result.Error(
                filePath = args.filePath,
                error = "User rejected edit operation"
            )
        }
        
        val normalizedPath = result.filePath.replace("\\", "/")
        val virtualFile = FileUtil.findVirtualFile(normalizedPath)
            ?: return ProxyAIEditTool.Result.Error(
                filePath = result.filePath,
                error = "File not found in IntelliJ VFS: ${result.filePath}"
            )

        val written = EditorUtil.writeDocumentContent(project, virtualFile, result.updatedCode)
        if (!written) {
            return ProxyAIEditTool.Result.Error(
                filePath = args.filePath,
                error = "Failed to write changes to document"
            )
        }
        
        return result
    }
}
