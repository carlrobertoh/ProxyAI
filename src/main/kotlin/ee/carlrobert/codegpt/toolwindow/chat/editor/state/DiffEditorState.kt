package ee.carlrobert.codegpt.toolwindow.chat.editor.state

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DiffHeaderActions
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DiffHeaderPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.HeaderConfig
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment
import ee.carlrobert.codegpt.util.file.FileUtil
import java.util.*
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent

abstract class DiffEditorState(
    override val editor: EditorEx,
    override val segment: Segment,
    override val project: Project,
    val diffViewer: UnifiedDiffViewer?,
    val virtualFile: VirtualFile?
) : EditorState {

    companion object {
        private val DIFF_REQUEST_KEY = Key.create<String>("codegpt.autoApply.diffRequest")

        fun createContextActionButton(
            text: String,
            icon: Icon,
            textColor: JBColor? = null,
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
                        if (textColor != null) {
                            foreground = textColor
                        }
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

    private lateinit var diffRequestId: UUID

    override fun createHeaderComponent(readOnly: Boolean): JComponent? {
        val languageMapping = FileUtil.findLanguageExtensionMapping(segment.language)
        val actions: DiffHeaderActions = object : DiffHeaderActions {
            override fun onAcceptAll() {
                applyAllChanges()
            }

            override fun onOpenDiff() {
                openDiff()
            }
        }

        return DiffHeaderPanel(
            HeaderConfig(
                project,
                editor,
                segment.filePath,
                languageMapping.key,
                false
            ),
            readOnly,
            actions
        )
    }

    abstract fun applyAllChanges()

    private fun openDiff() {
        if (virtualFile == null) {
            throw IllegalStateException("Virtual file is null")
        }

        diffViewer?.let { viewer ->
            diffRequestId = UUID.randomUUID()

            val diffContentFactory = DiffContentFactory.getInstance()
            val leftSide = diffContentFactory.create(project, virtualFile)
            val rightSideDoc = viewer.getDocument(Side.RIGHT).apply { setReadOnly(true) }
            val rightSide = diffContentFactory.create(project, rightSideDoc, virtualFile)
            var diffRequest = SimpleDiffRequest(
                "Code Diff",
                listOf(leftSide, rightSide),
                listOf("Original", "Modified")
            ).apply {
                val acceptAction = createContextActionButton(
                    CodeGPTBundle.get("shared.acceptAll"),
                    Icons.GreenCheckmark,
                    JBColor(0x2E7D32, 0x4CAF50)
                ) {
                    WriteCommandAction.runWriteCommandAction(project) {
                        applyAllChanges()
                    }
                }

                val rejectAction = createContextActionButton(
                    CodeGPTBundle.get("toolwindow.chat.editor.action.autoApply.reject"),
                    AllIcons.Actions.Close,
                    JBColor(0xB71C1C, 0xF44336)
                ) {
                    resetState(virtualFile)
                }

                putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(acceptAction, rejectAction))
                putUserData(DIFF_REQUEST_KEY, diffRequestId.toString())
            }

            service<DiffManager>().showDiff(project, diffRequest)
        }
    }

    private fun resetState(virtualFile: VirtualFile) {
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
}
