package ee.carlrobert.codegpt.toolwindow.chat.editor

import com.intellij.icons.AllIcons.General
import com.intellij.ide.actions.OpenFileAction
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.toolwindow.chat.editor.actions.*
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class HeaderPanel(
    private val project: Project,
    private val editorEx: EditorEx,
    filePath: String?,
    private val extension: String,
    private val readOnly: Boolean
) : JPanel(BorderLayout()) {

    private var actionToolbar: ActionToolbar? = null

    init {
        setupPanelAppearance()
        setupFilePathOrLanguageLabel(filePath)

        if (!readOnly) {
            actionToolbar = createHeaderActions()
            add(actionToolbar!!.component, BorderLayout.LINE_END)
        }
    }

    private fun setupPanelAppearance() {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(ColorUtil.fromHex("#48494b"), 1, 1, 0, 1),
            JBUI.Borders.empty(4)
        )
    }

    private fun setupFilePathOrLanguageLabel(filePath: String?) {
        val application = ApplicationManager.getApplication()
        if (filePath != null) {
            application.executeOnPooledThread {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                if (virtualFile == null) {
                    addComponent(createLanguageLabel(extension))
                } else {
                    addComponent(createFileLink(virtualFile))
                }
                CodeGPTKeys.TOOLWINDOW_EDITOR_FILE_DETAILS.set(
                    editorEx,
                    ToolWindowEditorFileDetails(filePath, virtualFile)
                )
            }
        } else {
            addComponent(createLanguageLabel(extension))
        }
    }

    private fun addComponent(component: JComponent) {
        runInEdt {
            add(component, BorderLayout.LINE_START)
        }
    }

    private fun createFileLink(virtualFile: VirtualFile): ActionLink {
        val name = virtualFile.name
        val fileActionLink = ActionLink(name)
        fileActionLink.setExternalLinkIcon()
        fileActionLink.addActionListener { OpenFileAction.openFile(virtualFile, project) }
        return fileActionLink
    }

    private fun createLanguageLabel(language: String): JBLabel {
        val label = JBLabel(language)
        label.border = JBUI.Borders.emptyLeft(4)
        label.foreground = JBColor.GRAY
        return label
    }

    private fun createHeaderActions(): ActionToolbar {
        val actionGroup = DefaultActionGroup("EDITOR_TOOLBAR_ACTION_GROUP", false)
        actionGroup.add(AutoApplyAction(project, editorEx, this))
        actionGroup.add(InsertAtCaretAction(editorEx))
        actionGroup.add(CopyAction(editorEx))
        actionGroup.addSeparator()
        actionGroup.add(createGearAction())

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("NAVIGATION_BAR_TOOLBAR", actionGroup, true)
        toolbar.layoutPolicy = ActionToolbar.NOWRAP_LAYOUT_POLICY
        toolbar.targetComponent = this
        toolbar.component.border = JBUI.Borders.empty()
        toolbar.updateActionsImmediately()
        return toolbar
    }

    private fun createGearActionsMenu(): JBPopupMenu {
        val menu = JBPopupMenu()
        menu.add(JBMenuItem(DiffAction(editorEx, menu.location)))
        menu.add(JBMenuItem(ReplaceSelectionAction(editorEx, menu.location)))
        menu.add(JBMenuItem(EditAction(editorEx)))
        menu.add(JBMenuItem(NewFileAction(editorEx, extension)))
        return menu
    }

    private fun createGearAction(): AnAction {
        return object : AnAction("Editor Actions", "Editor actions", General.GearPlain) {
            override fun actionPerformed(e: AnActionEvent) {
                val inputEvent = e.inputEvent
                if (inputEvent != null) {
                    createGearActionsMenu().show(inputEvent.component, 0, 0)
                }
            }
        }
    }

    fun setRightComponent(component: JPanel) {
        if (!readOnly) {
            remove(actionToolbar!!.component)
            add(component, BorderLayout.LINE_END)
            revalidate()
            repaint()
        }
    }

    fun restoreActionToolbar() {
        if (!readOnly) {
            val components = components
            for (component in components) {
                if (layout is BorderLayout &&
                    (layout as BorderLayout).getConstraints(component) == BorderLayout.LINE_END
                ) {
                    remove(component)
                }
            }

            add(actionToolbar!!.component, BorderLayout.LINE_END)
            actionToolbar!!.updateActionsImmediately()
            revalidate()
            repaint()
        }
    }
}
