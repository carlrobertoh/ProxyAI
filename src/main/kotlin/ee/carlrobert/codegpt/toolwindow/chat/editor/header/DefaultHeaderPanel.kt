package ee.carlrobert.codegpt.toolwindow.chat.editor.header

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.writeText
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.actions.*
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.StringUtil
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

    fun setLoading(label: String = "Loading...") {
        runInEdt {
            loadingLabel.text = label
            setRightPanelComponent(loadingLabel)
        }
    }

    fun handleDone() {
        runInEdt {
            setRightPanelComponent(createHeaderActions().component)
        }
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

        val directApplyThreshold = 0.85
        val coefficient = StringUtil.getDiceCoefficient(editor.document.text, file.readText())
        if (coefficient > directApplyThreshold) {
            runUndoTransparentWriteAction {
                file.writeText(editor.document.text)
            }
            return
        }

        val responseEditorPanel = editor.component.parent as? ResponseEditorPanel
            ?: throw IllegalStateException("Could not find corresponding ResponseEditorPanel")
        responseEditorPanel.applyCodeAsync(editor.document.text, file, editor)
        setLoading("Editing...")
    }

    private fun createToolbar(actionGroup: ActionGroup): ActionToolbar {
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("NAVIGATION_BAR_TOOLBAR", actionGroup, true)
        toolbar.layoutStrategy = ToolbarLayoutStrategy.NOWRAP_STRATEGY
        toolbar.targetComponent = this
        toolbar.component.border = JBUI.Borders.empty()
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
