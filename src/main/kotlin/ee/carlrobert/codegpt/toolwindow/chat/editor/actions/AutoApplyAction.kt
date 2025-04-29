package ee.carlrobert.codegpt.toolwindow.chat.editor.actions

import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.actions.ActionType
import ee.carlrobert.codegpt.actions.TrackableAction
import ee.carlrobert.codegpt.completions.CompletionClientProvider
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.chat.editor.HeaderPanel
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.util.EditorDiffUtil.createDiffRequest
import ee.carlrobert.llm.client.codegpt.request.AutoApplyRequest
import ee.carlrobert.llm.client.codegpt.response.CodeGPTException
import java.awt.FlowLayout
import java.util.*
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class AutoApplyAction(
    private val project: Project,
    private val toolwindowEditor: Editor,
    private val headerPanel: HeaderPanel,
) : TrackableAction(
    CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.title"),
    CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.description"),
    Icons.Lightning,
    ActionType.AUTO_APPLY
) {
    private lateinit var diffRequestId: UUID
    private var linksPanel: JPanel? = null

    companion object {
        private val DIFF_REQUEST_KEY = Key.create<String>("codegpt.autoApply.diffRequest")
    }

    override fun update(e: AnActionEvent) {
        if (GeneralSettings.getSelectedService() != ServiceType.CODEGPT) {
            e.presentation.disableAction(CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.disabledTitle"))
            return
        }

        val editorVirtualFile = CodeGPTKeys.TOOLWINDOW_EDITOR_VIRTUAL_FILE.get(toolwindowEditor)
        if (editorVirtualFile == null) {
            e.presentation.disableAction(CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.notApplicable"))
        }
    }

    override fun handleAction(event: AnActionEvent) {
        val editorVirtualFile = CodeGPTKeys.TOOLWINDOW_EDITOR_VIRTUAL_FILE.get(toolwindowEditor)
            ?: return

        val request = AutoApplyRequest().apply {
            suggestedChanges = toolwindowEditor.document.text
            fileContent = editorVirtualFile.readText()
        }

        val acceptLink = createDisabledActionLink(CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.accept"))
        val rejectLink = createDisabledActionLink(CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.reject"))

        val newLinksPanel = JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
            add(acceptLink)
            add(JBLabel("|"))
            add(rejectLink)
        }

        linksPanel = newLinksPanel
        headerPanel.setRightComponent(newLinksPanel)

        ProgressManager.getInstance().run(
            ApplyChangesBackgroundTask(
                project,
                request,
                { modifiedFileContent ->
                    acceptLink.isEnabled = true
                    acceptLink.addActionListener {
                        WriteCommandAction.runWriteCommandAction(project) {
                            editorVirtualFile.setBinaryContent(modifiedFileContent.toByteArray(editorVirtualFile.charset))
                        }
                        resetState(editorVirtualFile)
                    }

                    rejectLink.isEnabled = true
                    rejectLink.addActionListener {
                        resetState(editorVirtualFile)
                    }

                    showDiff(editorVirtualFile, modifiedFileContent)
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
                        resetState(editorVirtualFile)
                    }
                })
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    private fun Presentation.disableAction(disabledText: String? = null) {
        isEnabled = false
        icon = Icons.LightningDisabled
        text = disabledText
    }

    private fun showDiff(virtualFile: VirtualFile, modifiedFileContent: String) {
        diffRequestId = UUID.randomUUID()

        val tempDiffFile = LightVirtualFile(virtualFile.name, modifiedFileContent)
        val diffRequest = createDiffRequest(project, tempDiffFile, virtualFile).apply {
            putUserData(DIFF_REQUEST_KEY, diffRequestId.toString())

            val acceptAction = createContextActionButton(
                CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.accept"),
                Icons.GreenCheckmark,
                JBColor.GREEN
            ) {
                WriteCommandAction.runWriteCommandAction(project) {
                    virtualFile.setBinaryContent(modifiedFileContent.toByteArray(virtualFile.charset))
                }
                resetState(virtualFile)
            }

            val rejectAction = createContextActionButton(
                CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.reject"),
                AllIcons.Actions.Close,
                JBColor.RED
            ) {
                resetState(virtualFile)
            }

            putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(acceptAction, rejectAction))
        }

        runInEdt {
            service<DiffManager>().showDiff(project, diffRequest)
        }
    }

    private fun resetState(virtualFile: VirtualFile) {
        // Restore the action toolbar
        headerPanel.restoreActionToolbar()
        linksPanel = null

        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFile(virtualFile, true)

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

    private fun createDisabledActionLink(text: String): ActionLink {
        return ActionLink(text).apply {
            isEnabled = false
            autoHideOnDisable = false
        }
    }

    private fun createContextActionButton(
        text: String,
        icon: Icon,
        textColor: JBColor,
        onAction: (() -> Unit)
    ): AnAction {
        return object : AnAction(text, null, icon), CustomComponentAction {
            override fun actionPerformed(e: AnActionEvent) {
                onAction()
            }

            override fun createCustomComponent(
                presentation: Presentation,
                place: String
            ): JComponent {
                val button = JButton(presentation.text).apply {
                    font = JBUI.Fonts.smallFont()
                    isFocusable = false
                    isOpaque = true
                    foreground = textColor
                    preferredSize = JBUI.size(preferredSize.width, 26)
                    maximumSize = JBUI.size(Int.MAX_VALUE, 26)
                    addActionListener {
                        onAction()
                    }
                }
                return button
            }
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
