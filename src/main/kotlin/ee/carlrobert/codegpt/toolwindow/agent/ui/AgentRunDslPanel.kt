package ee.carlrobert.codegpt.toolwindow.agent.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolCallDescriptorFactory
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolCallView
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class AgentRunDslPanel(
    private val project: Project,
    private val vm: AgentRunViewModel
) {

    private val rowsPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val scrollPane = JBScrollPane(rowsPanel).apply {
        isOpaque = false
        border = JBUI.Borders.empty()
        viewport.isOpaque = false
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val expandLink = ActionLink("") {
        vm.expandAll = !vm.expandAll
        refresh()
    }.apply { isVisible = false }

    private val root = BorderLayoutPanel().apply {
        isOpaque = false
        border = JBUI.Borders.empty()
        addToCenter(scrollPane)
        addToBottom(expandLink)
    }

    val component: JComponent get() = root
    private val viewByEntryId = mutableMapOf<String, ToolCallView>()

    fun refresh() {
        viewByEntryId.clear()
        val items = vm.items
        val childrenByParent = LinkedHashMap<String, MutableList<RunEntry>>()
        val tasksInOrder = mutableListOf<RunEntry.TaskEntry>()
        val standalone = mutableListOf<RunEntry>()

        items.forEach { e ->
            when (e) {
                is RunEntry.TaskEntry -> {
                    tasksInOrder.add(e)
                    childrenByParent.putIfAbsent(e.id, mutableListOf())
                }

                else -> {
                    val pid = e.parentId
                    if (pid != null) {
                        childrenByParent.getOrPut(pid) { mutableListOf() }.add(e)
                    } else {
                        standalone.add(e)
                    }
                }
            }
        }

        val finalList = mutableListOf<RunEntry>()
        val taskIds = tasksInOrder.map { it.id }.toSet()
        tasksInOrder.forEach { t ->
            finalList.add(t)
            childrenByParent[t.id]?.let { kids ->
                if (vm.expandAll) {
                    finalList.addAll(kids)
                } else {
                    val from =
                        if (kids.size > AgentUiConfig.TASK_CHILDREN_MAX) kids.size - AgentUiConfig.TASK_CHILDREN_MAX else 0
                    finalList.addAll(kids.subList(from, kids.size))
                }
            }
        }
        if (standalone.isNotEmpty()) {
            finalList.addAll(standalone)
        }
        childrenByParent.forEach { (pid, kids) ->
            if (!taskIds.contains(pid)) {
                finalList.addAll(if (vm.expandAll) kids else kids.takeLast(AgentUiConfig.TASK_CHILDREN_MAX))
            }
        }

        rowsPanel.removeAll()
        finalList.forEach { value ->
            val descriptor = ToolCallDescriptorFactory.create(
                project = project,
                toolName = value.toolName,
                args = value.args ?: "",
                result = value.result,
                overrideKind = value.kind
            )
            val view = ToolCallView(descriptor)
            viewByEntryId[value.id] = view
            val leftIndent = if (value.parentId != null) 12 else 0
            rowsPanel.add(BorderLayoutPanel().apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, leftIndent, 2, 0)
                add(view, BorderLayout.CENTER)
            })
        }
        rowsPanel.revalidate()
        rowsPanel.repaint()
        val hasOverflow = tasksInOrder.any { task ->
            (childrenByParent[task.id]?.size ?: 0) > AgentUiConfig.TASK_CHILDREN_MAX
        } || childrenByParent.any { (pid, kids) ->
            !taskIds.contains(pid) && kids.size > AgentUiConfig.TASK_CHILDREN_MAX
        }
        expandLink.isVisible = hasOverflow
        expandLink.text = if (!vm.expandAll) "Show all" else "Collapse"
        expandLink.border = JBUI.Borders.emptyLeft(12)
        root.revalidate()
        root.repaint()
    }

    fun appendStreaming(entryId: String, text: String, isError: Boolean): Boolean {
        val view = viewByEntryId[entryId] ?: return false
        view.appendStreamingLine(text, isError)
        return true
    }

    fun complete(entryId: String, success: Boolean, result: Any?) {
        viewByEntryId[entryId]?.complete(success, result)
    }
}
