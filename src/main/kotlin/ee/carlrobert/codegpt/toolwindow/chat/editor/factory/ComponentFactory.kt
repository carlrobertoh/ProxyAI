package ee.carlrobert.codegpt.toolwindow.chat.editor.factory

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Key
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.actions.toolwindow.ReplaceCodeInMainEditorAction
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.SwingConstants

object ComponentFactory {

    val EXPANDED_KEY = Key.create<Boolean>("toolwindow.editor.isExpanded")
    const val MAX_VISIBLE_LINES = 10
    const val MIN_LINES_FOR_EXPAND = 8

    fun createExpandLinkPanel(editor: EditorEx): BorderLayoutPanel {
        return BorderLayoutPanel().apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(ColorUtil.fromHex("#48494b"), 0, 1, 1, 1),
                JBUI.Borders.empty(4)
            )
            addToCenter(createExpandLink(editor))
            putClientProperty("proxyai.expandedLinkPanel", true)
        }
    }

    fun createEditorActionGroup(editor: Editor): DefaultActionGroup {
        return DefaultActionGroup().apply {
            add(ReplaceCodeInMainEditorAction())
            (editor as? EditorEx)?.contextMenuGroupId?.let { groupId ->
                val actionManager = ActionManager.getInstance()
                val originalGroup = actionManager.getAction(groupId)
                if (originalGroup is ActionGroup) {
                    addAll(originalGroup.getChildren(null).toList())
                }
            }
        }
    }

    fun updateEditorPreferredSize(editor: EditorEx, expanded: Boolean) {
        val lineHeight = editor.lineHeight
        val lineCount = editor.document.lineCount

        if (lineCount <= MIN_LINES_FOR_EXPAND) {
            return
        }

        if (editor.isOneLineMode) {
            editor.component.preferredSize =
                Dimension(editor.component.width, editor.component.height)
        } else {
            if (expanded) {
                editor.component.preferredSize = null
            } else {
                val visibleLines = lineCount.coerceAtMost(MAX_VISIBLE_LINES)
                val desiredHeight = (lineHeight * visibleLines).coerceAtLeast(20)

                editor.component.preferredSize = Dimension(editor.component.width, desiredHeight)
            }
        }

        editor.component.revalidate()
        editor.component.repaint()
    }

    private fun createExpandLink(editor: EditorEx): ActionLink {
        val isExpanded = EXPANDED_KEY.get(editor) ?: false
        val linkText = getLinkText(isExpanded)

        return ActionLink(linkText) { event ->
            val currentState = EXPANDED_KEY.get(editor) ?: false
            val newState = !currentState
            EXPANDED_KEY.set(editor, newState)

            val source = event.source as ActionLink
            source.text = getLinkText(newState)
            source.icon = if (newState) Icons.CollapseAll else Icons.ExpandAll

            updateEditorPreferredSize(editor, newState)
        }.apply {
            icon = if (isExpanded) Icons.CollapseAll else Icons.ExpandAll
            font = JBUI.Fonts.smallFont()
            foreground = JBColor.GRAY
            horizontalAlignment = SwingConstants.CENTER
        }
    }

    private fun getLinkText(expanded: Boolean): String {
        return if (expanded) {
            CodeGPTBundle.get("toolwindow.chat.editor.action.collapse")
        } else {
            CodeGPTBundle.get("toolwindow.chat.editor.action.expand")
        }
    }
}