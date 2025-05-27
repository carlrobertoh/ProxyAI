package ee.carlrobert.codegpt.toolwindow.chat.editor.header

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.toolwindow.chat.editor.actions.*
import ee.carlrobert.codegpt.toolwindow.chat.editor.state.EditorStateManager
import ee.carlrobert.codegpt.util.EditorUtil
import javax.swing.JPanel

class DefaultHeaderPanel(config: HeaderConfig) : HeaderPanel(config) {

    private val loadingLabel: JBLabel by lazy {
        JBLabel(
            CodeGPTBundle.get("toolwindow.chat.editor.diff.thinking"),
            AnimatedIcon.Default(),
            JBLabel.LEFT
        )
    }

    init {
        setupUI()
    }

    override fun initializeRightPanel(rightPanel: JPanel) {
        if (config.loading) {
            rightPanel.add(loadingLabel)
        } else {
            rightPanel.add(createHeaderActions().component)
        }
    }

    fun setLoading() {
        setRightPanelComponent(loadingLabel)
    }

    fun handleDone() {
        setRightPanelComponent(createHeaderActions().component)
    }

    private fun createHeaderActions(): ActionToolbar {
        val editor = config.editorEx
        val project = config.project
        val actionGroup = DefaultActionGroup("EDITOR_TOOLBAR_ACTION_GROUP", false)
        if (config.readOnly) {
            actionGroup.add(CopyAction(editor))
        } else {
            actionGroup.add(AutoApplyAction(project, editor, config.filePath, virtualFile) {
                handleApply(project, editor)
            })
            actionGroup.add(CopyAction(editor))
            actionGroup.addSeparator()
            actionGroup.add(createGearAction())
        }
        return createToolbar(actionGroup)
    }

    private fun handleApply(project: Project, editor: EditorEx) {
        val file = virtualFile
            ?: EditorUtil.getSelectedEditor(project)?.virtualFile
            ?: throw IllegalStateException("Virtual file is null")

        setLoading()
        project.service<EditorStateManager>()
            .getCodeEditsAsync(editor.document.text, file, editor)
    }

    private fun createToolbar(actionGroup: ActionGroup): ActionToolbar {
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("NAVIGATION_BAR_TOOLBAR", actionGroup, true)
        toolbar.layoutStrategy = ToolbarLayoutStrategy.NOWRAP_STRATEGY
        toolbar.targetComponent = this
        toolbar.component.border = JBUI.Borders.empty()
        toolbar.updateActionsAsync()
        return toolbar
    }

    private fun createGearActionsMenu(): JBPopupMenu {
        val editor = config.editorEx
        val menu = JBPopupMenu()
        menu.add(JBMenuItem(DiffAction(editor, menu.location)))
        menu.add(JBMenuItem(ReplaceSelectionAction(editor, menu.location)))
        menu.add(JBMenuItem(InsertAtCaretAction(editor, menu.location)))
        menu.add(JBMenuItem(EditAction(editor)))
        menu.add(JBMenuItem(NewFileAction(editor, config.language)))
        return menu
    }

    private fun createGearAction(): AnAction {
        return object : AnAction("Editor Actions", "Editor actions", AllIcons.General.GearPlain) {
            override fun actionPerformed(e: AnActionEvent) {
                val inputEvent = e.inputEvent
                if (inputEvent != null) {
                    createGearActionsMenu().show(inputEvent.component, 0, 0)
                }
            }
        }
    }
}
