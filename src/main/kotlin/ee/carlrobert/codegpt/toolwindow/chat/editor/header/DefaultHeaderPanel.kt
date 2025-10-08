package ee.carlrobert.codegpt.toolwindow.chat.editor.header

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.completions.AutoApplyParameters
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType.INCEPTION
import ee.carlrobert.codegpt.settings.service.ServiceType.PROXYAI
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.actions.*
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.StringUtil
import okhttp3.sse.EventSource
import javax.swing.JPanel

class DefaultHeaderPanel(config: HeaderConfig) : HeaderPanel(config) {

    companion object {
        private val logger = thisLogger()
    }

    private var currentEventSource: EventSource? = null
    private val loadingPanel = LoadingPanel(
        CodeGPTBundle.get("toolwindow.chat.editor.diff.thinking")
    ) {
        handleDone()
    }

    init {
        setupUI()
    }

    override fun initializeRightPanel(rightPanel: JPanel) {
        if (config.loading) {
            rightPanel.add(loadingPanel)
        } else {
            rightPanel.add(createHeaderActions().component)
        }
    }

    fun setLoading(
        eventSource: EventSource? = null,
        label: String = CodeGPTBundle.get("toolwindow.chat.editor.diff.applying")
    ) {
        currentEventSource = eventSource
        loadingPanel.setText(label)
        loadingPanel.setEventSource(eventSource)

        runInEdt {
            setRightPanelComponent(loadingPanel)
        }
    }

    fun handleDone() {
        runInEdt {
            currentEventSource = null
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
        try {
            val file = virtualFile
                ?: EditorUtil.getSelectedEditor(project)?.virtualFile
                ?: throw IllegalStateException("Could not find file")
            val responseEditorPanel = editor.component.parent as? ResponseEditorPanel
                ?: throw IllegalStateException("Could not find editor panel")

            val directApplyThreshold = 0.85
            val coefficient =
                StringUtil.getDiceCoefficient(editor.document.text, EditorUtil.getFileContent(file))
            if (coefficient > directApplyThreshold) {
                responseEditorPanel.createDiffEditorForDirectApply(
                    EditorUtil.getFileContent(file),
                    editor.document.text,
                    file
                )
                return
            }

            val modelSelection =
                ModelSelectionService.getInstance()
                    .getModelSelectionForFeature(FeatureType.AUTO_APPLY);
            val params = AutoApplyParameters(editor.document.text, file)
            if (listOf(PROXYAI, INCEPTION).any { it == modelSelection.provider }) {
                responseEditorPanel.applyCode(modelSelection, params, this)
            } else {
                responseEditorPanel.applyCodeAsync(editor.document.text, file, editor, this)
            }
        } catch (e: Exception) {
            logger.error(e.message, e)
        }
    }

    private fun createToolbar(actionGroup: ActionGroup): ActionToolbar {
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("NAVIGATION_BAR_TOOLBAR", actionGroup, true)
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
