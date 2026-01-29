package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil.convertLineSeparators
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.agent.tools.EditArgsSnapshot
import ee.carlrobert.codegpt.agent.tools.WriteTool
import ee.carlrobert.codegpt.toolwindow.agent.ui.renderer.applyStringReplacement
import ee.carlrobert.codegpt.toolwindow.agent.ui.renderer.getFileContentWithFallback
import ee.carlrobert.codegpt.util.UpdateSnippetUtil
import kotlinx.coroutines.CompletableDeferred
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Manages the approval workflow for agent tool calls, particularly for Write and Edit operations.
 * Handles diff display, approval dialogs, and popup management.
 */
class AgentApprovalManager(
    private val project: Project
) {
    private val approvalPopups = ConcurrentHashMap<String, JBPopup>()

    companion object {
        private val AGENT_DIFF_REQUEST_KEY: Key<String> = Key.create("agent.approval.diffRequest")
    }

    fun openWriteApprovalDiff(
        args: WriteTool.Args,
        decision: CompletableDeferred<Boolean>
    ) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(args.filePath)
        val factory = DiffContentFactory.getInstance()

        val left = if (vf != null) factory.create(project, vf) else factory.create(project, "")
        val rightDoc =
            EditorFactory.getInstance().createDocument(convertLineSeparators(args.content))
                .apply {
                    setReadOnly(true)
                }
        val right = if (vf != null) factory.create(project, rightDoc, vf)
        else factory.create(project, rightDoc, FileTypes.PLAIN_TEXT)

        val request = SimpleDiffRequest(
            "Write File",
            listOf(left, right),
            listOf("Current", "Proposed")
        )
        attachApprovalActionsAndShowDiff(request, decision)
    }

    fun openEditApprovalDiff(
        args: EditArgsSnapshot,
        decision: CompletableDeferred<Boolean>,
        proposedContent: String? = null
    ) {
        val path = try {
            Paths.get(args.filePath).normalize().toString()
        } catch (_: Exception) {
            args.filePath
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            val factory = DiffContentFactory.getInstance()

            val current = getFileContentWithFallback(path)
            val proposed = proposedContent ?: run {
                val rawSnippet = if (args.newString.isNotBlank()) args.newString else args.oldString
                if (UpdateSnippetUtil.containsMarkers(rawSnippet)) {
                    current
                } else {
                    applyStringReplacement(current, args.oldString, args.newString, args.replaceAll)
                }
            }

            val left = if (vf != null) factory.create(project, vf) else factory.create(project, current)
            val rightDoc =
                EditorFactory.getInstance().createDocument(convertLineSeparators(proposed)).apply {
                    setReadOnly(true)
                }
            val right = if (vf != null) factory.create(project, rightDoc, vf)
            else factory.create(project, rightDoc, FileTypes.PLAIN_TEXT)

            val request = SimpleDiffRequest(
                "Edit File",
                listOf(left, right),
                listOf("Current", "Proposed")
            )

            runInEdt { attachApprovalActionsAndShowDiff(request, decision) }
        }
    }

    private fun attachApprovalActionsAndShowDiff(
        request: SimpleDiffRequest,
        decision: CompletableDeferred<Boolean>
    ) {
        val diffId = UUID.randomUUID().toString()
        val applyText = CodeGPTBundle.get("shared.apply")
        val rejectText = CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.reject")

        fun resolveApproval(approved: Boolean) {
            if (!decision.isCompleted) decision.complete(approved)
            ApplicationManager.getApplication().invokeLater {
                closeDiffById(diffId)
            }
        }

        val applyTop = createApplyAction(applyText) { resolveApproval(true) }
        val rejectTop = createRejectAction(rejectText) { resolveApproval(false) }

        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(applyTop, rejectTop))

        val bottomPanel = createBottomPanel(applyText, rejectText) { resolveApproval(it) }
        request.putUserData(DiffUserDataKeysEx.BOTTOM_PANEL, bottomPanel)
        request.putUserData(AGENT_DIFF_REQUEST_KEY, diffId)

        runInEdt { DiffManager.getInstance().showDiff(project, request) }

        decision.invokeOnCompletion {
            ApplicationManager.getApplication().invokeLater {
                closeDiffById(diffId)
            }
        }

        showDiffTabApprovalPopup(diffId, decision)
    }

    private fun createApplyAction(text: String, onApply: () -> Unit): AnAction =
        object : AnAction(text, null, Icons.GreenCheckmark), CustomComponentAction {
            override fun actionPerformed(e: AnActionEvent) = onApply()

            override fun createCustomComponent(
                presentation: Presentation,
                place: String
            ): JComponent =
                JButton(text).apply {
                    icon = Icons.GreenCheckmark
                    isFocusable = false
                    isContentAreaFilled = true
                    isOpaque = true
                    margin = Insets(JBUI.scale(4), JBUI.scale(10), JBUI.scale(4), JBUI.scale(10))
                    addActionListener { onApply() }
                }
        }

    private fun createRejectAction(text: String, onReject: () -> Unit): AnAction =
        object : AnAction(text, null, AllIcons.Actions.Close), CustomComponentAction {
            override fun actionPerformed(e: AnActionEvent) = onReject()

            override fun createCustomComponent(
                presentation: Presentation,
                place: String
            ): JComponent =
                JButton(text).apply {
                    isFocusable = false
                    isContentAreaFilled = true
                    isOpaque = true
                    margin = Insets(JBUI.scale(4), JBUI.scale(10), JBUI.scale(4), JBUI.scale(10))
                    addActionListener { onReject() }
                }
        }

    private fun createBottomPanel(
        applyText: String,
        rejectText: String,
        onDecision: (Boolean) -> Unit
    ): JPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(8))).apply {
            isOpaque = true
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(8)
            )

            val applyBtn = JButton(applyText).apply {
                isFocusable = false
                isContentAreaFilled = true
                isOpaque = true
                margin = Insets(JBUI.scale(6), JBUI.scale(12), JBUI.scale(6), JBUI.scale(12))
                addActionListener { onDecision(true) }
            }

            val rejectBtn = JButton(rejectText).apply {
                isFocusable = false
                isContentAreaFilled = true
                isOpaque = true
                putClientProperty("JButton.buttonType", "roundRect")
                margin = Insets(JBUI.scale(6), JBUI.scale(12), JBUI.scale(6), JBUI.scale(12))
                addActionListener { onDecision(false) }
            }

            add(applyBtn)
            add(rejectBtn)
        }

    private fun closeDiffById(diffId: String) {
        val manager = FileEditorManager.getInstance(project)
        val diffFile = manager.openFiles.firstOrNull {
            it is ChainDiffVirtualFile && it.chain.requests
                .filterIsInstance<SimpleDiffRequestChain.DiffRequestProducerWrapper>()
                .any { wrapper ->
                    wrapper.request.getUserData(AGENT_DIFF_REQUEST_KEY) == diffId
                }
        }
        if (diffFile != null) manager.closeFile(diffFile)

        approvalPopups.remove(diffId)?.let { if (!it.isDisposed) it.cancel() }
    }

    private fun findDiffFileById(diffId: String): VirtualFile? {
        val manager = FileEditorManager.getInstance(project)
        return manager.openFiles.firstOrNull {
            it is ChainDiffVirtualFile && it.chain.requests
                .filterIsInstance<SimpleDiffRequestChain.DiffRequestProducerWrapper>()
                .any { wrapper ->
                    wrapper.request.getUserData(AGENT_DIFF_REQUEST_KEY) == diffId
                }
        }
    }

    private fun showDiffTabApprovalPopup(diffId: String, decision: CompletableDeferred<Boolean>) {
        val applyText = CodeGPTBundle.get("shared.apply")
        val rejectText = CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.reject")

        val panel = BorderLayoutPanel().apply {
            isOpaque = true
            background = JBUI.CurrentTheme.CustomFrameDecorations.paneBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 12)
            )

            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                val applyBtn = JButton(applyText).apply {
                    isContentAreaFilled = true
                    isOpaque = true
                    margin = Insets(JBUI.scale(6), JBUI.scale(12), JBUI.scale(6), JBUI.scale(12))
                    addActionListener {
                        decision.complete(true)
                        closeDiffById(diffId)
                    }
                }
                val rejectBtn = JButton(rejectText).apply {
                    isContentAreaFilled = true
                    isOpaque = true
                    margin = Insets(JBUI.scale(6), JBUI.scale(12), JBUI.scale(6), JBUI.scale(12))
                    addActionListener {
                        decision.complete(false)
                        closeDiffById(diffId)
                    }
                }
                add(applyBtn)
                add(rejectBtn)
            }
            add(buttons, BorderLayout.CENTER)
        }

        runInEdt {
            val diffFile = findDiffFileById(diffId) ?: return@runInEdt
            val manager = FileEditorManager.getInstance(project)
            val fileEditor = manager.getSelectedEditor(diffFile)
                ?: manager.getEditors(diffFile).firstOrNull()
            val target = fileEditor?.component ?: return@runInEdt
            if (!target.isShowing) return@runInEdt

            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, null)
                .setRequestFocus(false)
                .setFocusable(false)
                .setMovable(false)
                .setResizable(false)
                .setShowBorder(false)
                .setCancelOnClickOutside(false)
                .setCancelOnOtherWindowOpen(true)
                .createPopup()

            approvalPopups[diffId] = popup

            val margin = JBUI.scale(12)
            val size = panel.preferredSize
            val onScreen = target.locationOnScreen
            val x = onScreen.x + target.width - size.width - margin
            val y = onScreen.y + target.height - size.height - margin
            popup.showInScreenCoordinates(target, Point(x, y))

            val listener = object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) = relocate()
                override fun componentMoved(e: ComponentEvent?) = relocate()

                private fun relocate() {
                    if (popup.isDisposed) return
                    if (!target.isShowing) return
                    val loc = target.locationOnScreen
                    val nx = loc.x + target.width - size.width - margin
                    val ny = loc.y + target.height - size.height - margin
                    popup.setLocation(Point(nx, ny))
                }
            }
            target.addComponentListener(listener)
            Disposer.register(popup) {
                try {
                    target.removeComponentListener(listener)
                } catch (_: Exception) {
                }
            }
        }
    }
}
