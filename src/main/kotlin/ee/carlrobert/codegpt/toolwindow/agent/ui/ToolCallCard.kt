package ee.carlrobert.codegpt.toolwindow.agent.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolCallDescriptorFactory
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolCallView
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolKind

class ToolCallCard(
    project: Project,
    private val toolName: String,
    private val args: Any?,
    private val overrideKind: ToolKind? = null
) : JBPanel<ToolCallCard>() {

    private val view: ToolCallView
    private val projectId: String = project.locationHash

    init {
        layout = java.awt.BorderLayout()
        isOpaque = false
        border = JBUI.Borders.empty()

        val descriptor = ToolCallDescriptorFactory.create(
            toolName = toolName,
            args = args ?: Unit,
            result = null,
            projectId = projectId,
            overrideKind = overrideKind
        )
        view = ToolCallView(descriptor)
        add(view, java.awt.BorderLayout.CENTER)
    }

    fun complete(success: Boolean, result: Any?) {
        val updated = ToolCallDescriptorFactory.create(
            toolName = toolName,
            args = args ?: Unit,
            result = result,
            projectId = projectId,
            overrideKind = overrideKind
        )
        view.refreshDescriptor(updated)
        view.complete(success, result)
    }

    fun appendStreamingLine(text: String, isError: Boolean) {
        view.appendStreamingLine(text, isError)
    }
}
