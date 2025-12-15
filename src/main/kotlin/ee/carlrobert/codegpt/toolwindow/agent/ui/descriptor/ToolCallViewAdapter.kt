package ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor

import ee.carlrobert.codegpt.toolwindow.agent.ui.RunEntry

object ToolCallViewAdapter {

    fun build(entry: RunEntry): ToolCallView {
        val descriptor = createDescriptor(entry)
        return ToolCallView(descriptor)
    }

    private fun createDescriptor(entry: RunEntry): ToolCallDescriptor {
        return when (entry) {
            is RunEntry.TaskEntry -> createTaskDescriptor(entry)
            is RunEntry.ReadEntry -> createReadDescriptor(entry)
            is RunEntry.BashEntry -> createBashDescriptor(entry)
            is RunEntry.IntelliJSearchEntry -> createIntelliJSearchDescriptor(entry)
            is RunEntry.WebEntry -> createWebDescriptor(entry)
            is RunEntry.WriteEntry -> createWriteDescriptor(entry)
            is RunEntry.EditEntry -> createEditDescriptor(entry)
            is RunEntry.LibraryResolveEntry -> createLibraryResolveDescriptor(entry)
            is RunEntry.LibraryDocsEntry -> createLibraryDocsDescriptor(entry)
            else -> createOtherDescriptor(entry)
        }
    }

    private fun createTaskDescriptor(entry: RunEntry.TaskEntry): ToolCallDescriptor {
        return ToolCallDescriptorFactory.create(
            toolName = "Task",
            args = entry.args ?: "",
            result = entry.result,
            overrideKind = ToolKind.TASK
        )
    }

    private fun createReadDescriptor(entry: RunEntry.ReadEntry): ToolCallDescriptor {
        return ToolCallDescriptorFactory.create(
            toolName = "Read",
            args = entry.args ?: "",
            result = entry.result,
            overrideKind = ToolKind.READ
        )
    }

    private fun createBashDescriptor(entry: RunEntry.BashEntry): ToolCallDescriptor {
        return ToolCallDescriptorFactory.create(
            toolName = "Bash",
            args = entry.args ?: "",
            result = entry.result,
            overrideKind = ToolKind.BASH
        )
    }

    private fun createIntelliJSearchDescriptor(entry: RunEntry.IntelliJSearchEntry): ToolCallDescriptor {
        return ToolCallDescriptorFactory.create(
            toolName = "IntelliJSearch",
            args = entry.args ?: "",
            result = entry.result,
            overrideKind = ToolKind.SEARCH
        )
    }

    private fun createWebDescriptor(entry: RunEntry.WebEntry): ToolCallDescriptor {
        return ToolCallDescriptorFactory.create(
            toolName = "WebSearch",
            args = entry.args ?: "",
            result = entry.result,
            overrideKind = ToolKind.WEB
        )
    }

    private fun createWriteDescriptor(entry: RunEntry.WriteEntry): ToolCallDescriptor {
        return ToolCallDescriptorFactory.create(
            toolName = "Write",
            args = entry.args ?: "",
            result = entry.result,
            overrideKind = ToolKind.WRITE
        )
    }

    private fun createEditDescriptor(entry: RunEntry.EditEntry): ToolCallDescriptor {
        return ToolCallDescriptorFactory.create(
            toolName = "Edit",
            args = entry.args ?: "",
            result = entry.result,
            overrideKind = ToolKind.EDIT
        )
    }

    private fun createLibraryResolveDescriptor(entry: RunEntry.LibraryResolveEntry): ToolCallDescriptor {
        return ToolCallDescriptorFactory.create(
            toolName = "ResolveLibraryId",
            args = entry.args ?: "",
            result = entry.result,
            overrideKind = ToolKind.LIBRARY_RESOLVE
        )
    }

    private fun createLibraryDocsDescriptor(entry: RunEntry.LibraryDocsEntry): ToolCallDescriptor {
        return ToolCallDescriptorFactory.create(
            toolName = "GetLibraryDocs",
            args = entry.args ?: "",
            result = entry.result,
            overrideKind = ToolKind.LIBRARY_DOCS
        )
    }

    private fun createOtherDescriptor(entry: RunEntry): ToolCallDescriptor {
        return ToolCallDescriptorFactory.create(
            toolName = entry.kind.name,
            args = "",
            result = null,
            overrideKind = ToolKind.OTHER
        )
    }
}
