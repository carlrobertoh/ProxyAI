package ee.carlrobert.codegpt.toolwindow.agent.ui

import com.intellij.openapi.application.runInEdt
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.agent.tools.TodoWriteTool
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

class TodoListPanel : BorderLayoutPanel() {

    private var currentTodos: List<TodoWriteTool.TodoItem> = emptyList()
    private var expanded = false
    private val expandLink = ActionLink("") {
        expanded = !expanded
        rebuild()
    }.apply {
        isVisible = false
        font = JBUI.Fonts.smallFont()
    }
    private val headerPanel = BorderLayoutPanel()
        .addToLeft(JLabel("Tasks").apply { font = JBUI.Fonts.label().asBold() })
        .addToRight(expandLink)
        .andTransparent()
        .withBorder(JBUI.Borders.emptyBottom(8))

    private val contentHolder = BorderLayoutPanel()

    init {
        isVisible = false
        andTransparent()
        withBorder(JBUI.Borders.empty(8, 0))
        addToTop(headerPanel)
        addToCenter(contentHolder)
    }

    fun updateTodos(todos: List<TodoWriteTool.TodoItem>) {
        currentTodos = todos
        runInEdt { rebuild() }
    }

    fun clearTodos() {
        runInEdt {
            currentTodos = emptyList()
            contentHolder.removeAll()
            isVisible = false
            revalidate()
            repaint()
        }
    }

    private fun rebuild() {
        val shown = if (getCurrent() != null) 1 + if (getNext() != null) 1 else 0 else 0
        val remaining = (currentTodos.size - shown).coerceAtLeast(0)
        expandLink.text = if (expanded) "Collapse" else "Show $remaining more"
        expandLink.isVisible = expanded || currentTodos.size > 2
        contentHolder.removeAll()
        val content = if (expanded) buildExpandedContent() else buildCollapsedContent()
        contentHolder.addToCenter(content)
        contentHolder.revalidate()
        contentHolder.repaint()
    }

    private fun buildCollapsedContent(): JPanel {
        val current = getCurrent()
        val container = JPanel()
        container.isOpaque = false
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        val currentText = currentLine(current)
        val currentComp = createWrappingText(currentText, bold = true, small = true, gray = false)
        container.add(currentComp)

        if (current != null) {
            val currentIdx = currentTodos.indexOf(current)
            val isLastItem = currentIdx == currentTodos.size - 1

            if (isLastItem && currentIdx > 0) {
                val previous = currentTodos[currentIdx - 1]
                val previousLine = buildString {
                    append("Previous: ")
                    append(taskText(previous))
                }
                val previousComp =
                    createWrappingText(previousLine, bold = false, small = true, gray = true)
                container.add(previousComp)
            } else {
                val next = getNext()
                if (next != null) {
                    val nextLine = buildString {
                        append("Next: ")
                        append(taskText(next))
                    }
                    val nextComp =
                        createWrappingText(nextLine, bold = false, small = true, gray = true)
                    container.add(nextComp)
                }
            }
        }
        return BorderLayoutPanel().andTransparent().addToCenter(container)
    }

    private fun buildExpandedContent(): JPanel {
        val listPanel = JPanel(GridBagLayout()).apply { isOpaque = false }
        currentTodos.forEachIndexed { index, todo ->
            val item = JPanel(BorderLayout(4, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8)
                val cb = JBCheckBox().apply {
                    isSelected = todo.status == TodoWriteTool.TodoStatus.COMPLETED
                    isEnabled = false
                    isOpaque = false
                    margin = JBUI.emptyInsets()
                }
                val label = createWrappingText(
                    taskText(todo),
                    bold = todo.status == TodoWriteTool.TodoStatus.IN_PROGRESS,
                    small = false,
                    gray = todo.status == TodoWriteTool.TodoStatus.COMPLETED
                )
                add(cb, BorderLayout.WEST)
                add(label, BorderLayout.CENTER)
            }
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = index
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = JBUI.insets(2, 0, 2, 0)
            }
            listPanel.add(item, gbc)
        }
        val scroll = JScrollPane(listPanel).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        return BorderLayoutPanel().apply {
            isOpaque = false
            add(scroll, BorderLayout.CENTER)
        }
    }

    private fun getCurrent(): TodoWriteTool.TodoItem? {
        return currentTodos.firstOrNull { it.status == TodoWriteTool.TodoStatus.IN_PROGRESS }
            ?: currentTodos.firstOrNull { it.status == TodoWriteTool.TodoStatus.PENDING }
            ?: currentTodos.firstOrNull()
    }

    private fun getNext(): TodoWriteTool.TodoItem? {
        val current = getCurrent() ?: return null
        val idx = currentTodos.indexOf(current)
        return currentTodos.drop(idx + 1)
            .firstOrNull { it.status != TodoWriteTool.TodoStatus.COMPLETED }
            ?: currentTodos.firstOrNull { it.status == TodoWriteTool.TodoStatus.PENDING && it != current }
    }

    private fun currentLine(current: TodoWriteTool.TodoItem?): String {
        return current?.let {
            val total = currentTodos.size
            val pos = currentTodos.indexOf(it) + 1
            buildString {
                append(taskText(it))
                if (total > 0) {
                    append("  (")
                    append(pos)
                    append('/')
                    append(total)
                    append(')')
                }
            }
        } ?: "No tasks"
    }

    private fun taskText(todo: TodoWriteTool.TodoItem): String {
        return if (todo.status == TodoWriteTool.TodoStatus.IN_PROGRESS) todo.activeForm else todo.content
    }

    private fun createWrappingText(
        text: String,
        bold: Boolean,
        small: Boolean,
        gray: Boolean
    ): JBTextArea {
        return JBTextArea(text).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(0, 0, 2, 0)
            font = when {
                bold && !small -> JBUI.Fonts.label().asBold()
                bold && small -> JBUI.Fonts.smallFont().asBold()
                small -> JBUI.Fonts.smallFont()
                else -> JBUI.Fonts.label()
            }
            foreground =
                if (gray) JBUI.CurrentTheme.Label.disabledForeground() else JBUI.CurrentTheme.Label.foreground()
        }
    }
}
