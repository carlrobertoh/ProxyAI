package ee.carlrobert.codegpt.toolwindow.agent.ui

import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolKind

sealed class RunEntry {
    abstract val id: String
    abstract val parentId: String?
    abstract val kind: ToolKind
    abstract fun withAnyResult(result: Any?): RunEntry

    data class ReadEntry(
        override val id: String,
        override val parentId: String? = null,
        val args: ReadTool.Args? = null,
        val result: ReadTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.READ
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? ReadTool.Result)
    }

    data class IntelliJSearchEntry(
        override val id: String,
        override val parentId: String? = null,
        val args: IntelliJSearchTool.Args? = null,
        val result: IntelliJSearchTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.SEARCH
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? IntelliJSearchTool.Result)
    }

    data class BashEntry(
        override val id: String,
        override val parentId: String? = null,
        val args: BashTool.Args? = null,
        val result: BashTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.BASH
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? BashTool.Result)
    }

    data class WebEntry(
        override val id: String,
        override val parentId: String? = null,
        val args: WebSearchTool.Args? = null,
        val result: WebSearchTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.WEB
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? WebSearchTool.Result)
    }

    data class WriteEntry(
        override val id: String,
        override val parentId: String? = null,
        val args: WriteTool.Args? = null,
        val result: WriteTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.WRITE
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? WriteTool.Result)
    }

    data class EditEntry(
        override val id: String,
        override val parentId: String? = null,
        val args: EditTool.Args? = null,
        val result: EditTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.EDIT
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? EditTool.Result)
    }

    data class TaskEntry(
        override val id: String,
        override val parentId: String? = null,
        val args: TaskTool.Args? = null,
        val result: TaskTool.Result? = null,
        val summary: TaskSummary? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.TASK
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? TaskTool.Result)
    }

    data class LibraryResolveEntry(
        override val id: String,
        override val parentId: String? = null,
        val args: ResolveLibraryIdTool.Args? = null,
        val result: ResolveLibraryIdTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.LIBRARY_RESOLVE
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? ResolveLibraryIdTool.Result)
    }

    data class LibraryDocsEntry(
        override val id: String,
        override val parentId: String? = null,
        val args: GetLibraryDocsTool.Args? = null,
        val result: GetLibraryDocsTool.Result? = null,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.LIBRARY_DOCS
        override fun withAnyResult(result: Any?): RunEntry =
            copy(result = result as? GetLibraryDocsTool.Result)
    }

    data class OtherEntry(
        override val id: String,
        override val parentId: String? = null,
        val name: String,
    ) : RunEntry() {
        override val kind: ToolKind = ToolKind.OTHER
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
