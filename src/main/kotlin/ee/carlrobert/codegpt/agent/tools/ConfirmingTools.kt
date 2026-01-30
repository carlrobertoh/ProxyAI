package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.Tool

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
