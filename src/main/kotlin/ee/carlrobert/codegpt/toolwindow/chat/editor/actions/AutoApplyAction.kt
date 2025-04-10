package ee.carlrobert.codegpt.toolwindow.chat.editor.actions

import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.actions.ActionType
import ee.carlrobert.codegpt.actions.TrackableAction
import ee.carlrobert.codegpt.completions.CompletionClientProvider
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.util.EditorDiffUtil.createDiffRequest
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.EditorUtil.getSelectedEditor
import ee.carlrobert.llm.client.codegpt.request.AutoApplyRequest
import ee.carlrobert.llm.client.codegpt.response.CodeGPTException
import java.awt.FlowLayout
import java.util.*
import javax.swing.JButton
import javax.swing.JPanel

class AutoApplyAction(
    private val project: Project,
    private val toolwindowEditor: Editor,
    private val headerPanel: JPanel,
) : TrackableAction(
    CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.title"),
    CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.description"),
    Icons.Lightning,
    ActionType.AUTO_APPLY
) {
    private lateinit var diffRequestId: UUID

    companion object {
        private val DIFF_REQUEST_KEY = Key.create<String>("codegpt.autoApply.diffRequest")
    }

    override fun update(e: AnActionEvent) {
        val isCodeGPTSelected = GeneralSettings.getSelectedService() == ServiceType.CODEGPT
        if (isCodeGPTSelected) {
            validateAndUpdatePresentation(e)
        } else {
            e.presentation.disableAction(CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.disabledTitle"))
        }
    }

    override fun handleAction(event: AnActionEvent) {
        val mainEditor = getSelectedEditor(project)
            ?: throw IllegalStateException("Unable to find active editor")
        val request = AutoApplyRequest().apply {
            suggestedChanges = toolwindowEditor.document.text
            fileContent = mainEditor.document.text
        }

        headerPanel.getComponent(1).isVisible = false

        val acceptLink =
            createDisabledActionLink(CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.accept"))
        val rejectLink =
            createDisabledActionLink(CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.reject"))

        val actionsPanel = JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0)).apply {
            border = JBUI.Borders.empty(4, 0)
            add(acceptLink)
            add(JBLabel("|"))
            add(rejectLink)
        }
        headerPanel.add(actionsPanel)

        ProgressManager.getInstance().run(
            ApplyChangesBackgroundTask(
                project,
                request,
                { modifiedFileContent ->
                    acceptLink.setupLink(mainEditor, actionsPanel) {
                        EditorUtil.updateEditorDocument(mainEditor, modifiedFileContent)
                    }
                    rejectLink.setupLink(mainEditor, actionsPanel)
                    showDiff(mainEditor, modifiedFileContent)
                },
                {
                    val errorMessage = if (it is CodeGPTException) {
                        it.detail
                    } else {
                        CodeGPTBundle.get(
                            "toolwindow.chat.editor.action.autoApply.error",
                            it.message
                        )
                    }
                    OverlayUtil.showNotification(errorMessage, NotificationType.ERROR)
                    runInEdt {
                        resetState(mainEditor, actionsPanel)
                    }
                })
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    private fun validateAndUpdatePresentation(e: AnActionEvent) {
        val activeEditor = e.project?.let { getSelectedEditor(project) }
        if (activeEditor == null) {
            e.presentation.disableAction(CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.noActiveFile"))
            return
        }

        val fileTokenCount = service<EncodingManager>().countTokens(activeEditor.document.text)
        if (fileTokenCount > 4096) {
            e.presentation.disableAction(CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.fileTooLarge"))
        } else {
            e.presentation.enableAction()
        }
    }

    private fun Presentation.disableAction(disabledText: String) {
        isEnabled = false
        icon = Icons.LightningDisabled
        text = disabledText
    }

    private fun Presentation.enableAction() {
        isEnabled = true
        icon = Icons.Lightning
        text = CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.title")
    }

    private fun JButton.setupLink(
        mainEditor: Editor,
        actionsPanel: JPanel,
        onAction: (() -> Unit)? = null
    ) {
        isEnabled = true
        addActionListener {
            resetState(mainEditor, actionsPanel)
            onAction?.invoke()
        }
    }

    private fun showDiff(mainEditor: Editor, modifiedFileContent: String) {
        diffRequestId = UUID.randomUUID()

        val tempDiffFile = LightVirtualFile(mainEditor.virtualFile.name, modifiedFileContent)
        val diffRequest = createDiffRequest(project, tempDiffFile, mainEditor).apply {
            putUserData(DIFF_REQUEST_KEY, diffRequestId.toString())
        }

        runInEdt {
            service<DiffManager>().showDiff(project, diffRequest)
        }
    }

    private fun createDisabledActionLink(text: String): ActionLink {
        return ActionLink(text).apply {
            isEnabled = false
            autoHideOnDisable = false
        }
    }

    private fun resetState(mainEditor: Editor, actionsPanel: JPanel) {
        headerPanel.remove(actionsPanel)
        headerPanel.getComponent(1).isVisible = true
        val fileEditorManager = project.service<FileEditorManager>()
        fileEditorManager.openFile(mainEditor.virtualFile, true)

        val diffFile = fileEditorManager.openFiles.firstOrNull {
            it is ChainDiffVirtualFile && it.chain.requests
                .filterIsInstance<SimpleDiffRequestChain.DiffRequestProducerWrapper>()
                .any { chainRequest ->
                    chainRequest.request.getUserData(DIFF_REQUEST_KEY) == diffRequestId.toString()
                }
        }
        if (diffFile != null) {
            fileEditorManager.closeFile(diffFile)
        }
    }
}

internal class ApplyChangesBackgroundTask(
    project: Project,
    private val request: AutoApplyRequest,
    private val onSuccess: (modifiedFileContent: String) -> Unit,
    private val onFailure: (ex: Exception) -> Unit,
) : Task.Backgroundable(
    project,
    CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.taskTitle"),
    true
) {

    override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = false
        indicator.fraction = 1.0
        indicator.text = CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.loadingMessage")

        try {
            val modifiedFileContent = CompletionClientProvider.getCodeGPTClient()
                .applySuggestedChanges(request)
                .modifiedFileContent
            onSuccess(modifiedFileContent)
        } catch (ex: Exception) {
            onFailure(ex)
        }
    }
}