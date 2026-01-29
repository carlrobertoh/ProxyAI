package ee.carlrobert.codegpt.agent.tools

data class EditArgsSnapshot(
    val filePath: String,
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean,
    val shortDescription: String
)

fun EditTool.Args.toSnapshot(): EditArgsSnapshot {
    return EditArgsSnapshot(
        filePath = filePath,
        oldString = oldString,
        newString = newString,
        replaceAll = replaceAll,
        shortDescription = shortDescription
    )
}

fun ProxyAIEditTool.Args.toSnapshot(): EditArgsSnapshot {
    return EditArgsSnapshot(
        filePath = filePath,
        oldString = "",
        newString = updateSnippet,
        replaceAll = false,
        shortDescription = shortDescription
    )
}

fun snapshotFromEditArgs(args: Any?): EditArgsSnapshot? {
    return when (args) {
        is EditTool.Args -> args.toSnapshot()
        is ProxyAIEditTool.Args -> args.toSnapshot()
        is EditArgsSnapshot -> args
        else -> null
    }
}