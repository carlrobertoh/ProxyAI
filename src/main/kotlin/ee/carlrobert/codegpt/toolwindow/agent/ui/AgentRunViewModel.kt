package ee.carlrobert.codegpt.toolwindow.agent.ui

import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolKind

sealed class RunEntry {
    abstract val id: String
    abstract val parentId: String?
    abstract val kind: ToolKind
    abstract val toolName: String
    abstract val args: Any?
    abstract val result: Any?
    abstract fun withAnyResult(result: Any?): RunEntry

    data class ReadEntry(
        override val id: String,
        override val parentId: String? = null,
        override val args: ReadTool.Args? = null,
        override val result: ReadTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.READ
        override val toolName: String = "Read"
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? ReadTool.Result)
    }

    data class IntelliJSearchEntry(
        override val id: String,
        override val parentId: String? = null,
        override val args: IntelliJSearchTool.Args? = null,
        override val result: IntelliJSearchTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.SEARCH
        override val toolName: String = "IntelliJSearch"
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? IntelliJSearchTool.Result)
    }

    data class BashEntry(
        override val id: String,
        override val parentId: String? = null,
        override val args: BashTool.Args? = null,
        override val result: BashTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.BASH
        override val toolName: String = "Bash"
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? BashTool.Result)
    }

    data class WebEntry(
        override val id: String,
        override val parentId: String? = null,
        override val args: WebSearchTool.Args? = null,
        override val result: WebSearchTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.WEB
        override val toolName: String = "WebSearch"
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? WebSearchTool.Result)
    }

    data class WriteEntry(
        override val id: String,
        override val parentId: String? = null,
        override val args: WriteTool.Args? = null,
        override val result: WriteTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.WRITE
        override val toolName: String = "Write"
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? WriteTool.Result)
    }

    data class EditEntry(
        override val id: String,
        override val parentId: String? = null,
        override val args: EditTool.Args? = null,
        override val result: EditTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.EDIT
        override val toolName: String = "Edit"
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? EditTool.Result)
    }

    data class TaskEntry(
        override val id: String,
        override val parentId: String? = null,
        override val args: TaskTool.Args? = null,
        override val result: TaskTool.Result? = null,
        val summary: TaskSummary? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.TASK
        override val toolName: String = "Task"
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? TaskTool.Result)
    }

    data class McpEntry(
        override val id: String,
        override val parentId: String? = null,
        override val args: McpTool.Args? = null,
        override val result: McpTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.MCP
        override val toolName: String = "MCP"
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? McpTool.Result)
    }

    data class LibraryResolveEntry(
        override val id: String,
        override val parentId: String? = null,
        override val args: ResolveLibraryIdTool.Args? = null,
        override val result: ResolveLibraryIdTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.LIBRARY_RESOLVE
        override val toolName: String = "ResolveLibraryId"
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? ResolveLibraryIdTool.Result)
    }

    data class LibraryDocsEntry(
        override val id: String,
        override val parentId: String? = null,
        override val args: GetLibraryDocsTool.Args? = null,
        override val result: GetLibraryDocsTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.LIBRARY_DOCS
        override val toolName: String = "GetLibraryDocs"
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? GetLibraryDocsTool.Result)
    }

    data class OtherEntry(
        override val id: String,
        override val parentId: String? = null,
        val name: String,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.OTHER
        override val toolName: String get() = name
        override val args: Any? = null
        override val result: Any? = null
        override fun withAnyResult(result: Any?): RunEntry = this
    }
}

class AgentRunViewModel {
    var expandAll: Boolean = false
    var items: List<RunEntry> = emptyList()

    fun addEntry(entry: RunEntry) {
        items = items + entry
    }

    fun completeEntry(id: String, result: Any?) {
        val updated = items.map { existing ->
            if (existing.id != id) existing else when (existing) {
                is RunEntry.TaskEntry -> {
                    val children = items.filter { it.parentId == existing.id }
                    val calls = children.size

                    val totalTokens = when (val taskResult = result as? TaskTool.Result) {
                        null -> TokenCounter.countForEntries(children)
                        else -> if (taskResult.totalTokens > 0) taskResult.totalTokens else TokenCounter.countForEntries(
                            children
                        )
                    }.toLong()

                    existing.copy(
                        result = result as? TaskTool.Result,
                        summary = TaskSummary(calls, totalTokens)
                    )
                }

                else -> existing.withAnyResult(result)
            }
        }
        items = updated
    }
}

data class TaskSummary(
    val toolCalls: Int,
    val tokens: Long,
)
