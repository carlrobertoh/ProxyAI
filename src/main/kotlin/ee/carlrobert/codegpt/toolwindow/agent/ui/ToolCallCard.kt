package ee.carlrobert.codegpt.toolwindow.agent.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolCallDescriptorFactory
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolCallView
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolKind
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolCallDescriptor
import java.awt.BorderLayout

class ToolCallCard(
    private val project: Project,
    private val toolName: String,
    private val args: Any?,
    private val overrideKind: ToolKind? = null
) : JBPanel<ToolCallCard>() {

    private val view: ToolCallView

    init {
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.empty()

        val descriptor = ToolCallDescriptorFactory.create(
            project = project,
            toolName = toolName,
            args = args ?: Unit,
            result = null,
            overrideKind = overrideKind
        )
        view = ToolCallView(descriptor)
        add(view, BorderLayout.CENTER)
    }

    fun complete(success: Boolean, result: Any?) {
        val updated = ToolCallDescriptorFactory.create(
            project = project,
            toolName = toolName,
            args = args ?: Unit,
            result = result,
            overrideKind = overrideKind
        )
        view.refreshDescriptor(updated)
        view.complete(success, result)
    }

    fun updateDescriptor(transform: (ToolCallDescriptor) -> ToolCallDescriptor) {
        val updated = transform(view.getDescriptor())
        view.refreshDescriptor(updated)
    }

    fun getDescriptor(): ToolCallDescriptor = view.getDescriptor()

    fun appendStreamingLine(text: String, isError: Boolean) {
        view.appendStreamingLine(text, isError)
    }
}
