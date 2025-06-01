package ee.carlrobert.codegpt.toolwindow.chat.editor.header

import com.intellij.diff.tools.fragmented.UnifiedDiffChange
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.OpenFileAction
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.writeText
import com.intellij.ui.*
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.diff.DiffStatsComponent
import ee.carlrobert.codegpt.util.file.FileUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

data class HeaderConfig(
    val project: Project,
    val editorEx: EditorEx,
    val filePath: String?,
    val language: String,
    val readOnly: Boolean,
    val error: String? = null,
    val loading: Boolean = false
)

abstract class HeaderPanel(protected val config: HeaderConfig) : BorderLayoutPanel() {

    companion object {
        private val logger = thisLogger()
    }

    private val statsComponent = SimpleColoredComponent().apply {
        font = JBUI.Fonts.smallFont()
    }
    private val errorLabel = JBLabel(AllIcons.General.Error).apply {
        isVisible = config.error != null
        border = JBUI.Borders.emptyRight(4)
    }
    private val rightPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        alignmentY = 0.5f
        isOpaque = false
    }

    protected var virtualFile: VirtualFile? = FileUtil.resolveVirtualFile(config.filePath)

    protected abstract fun initializeRightPanel(rightPanel: JPanel)

    fun updateDiffStats(changes: List<UnifiedDiffChange>) {
        runInEdt {
            DiffStatsComponent.updateStatsComponent(statsComponent, changes)
            revalidate()
            repaint()
        }
    }

    protected fun setupUI() {
        setupPanelAppearance()
        addToLeft(createLeftPanel(virtualFile))
        rightPanel.removeAll()
        initializeRightPanel(rightPanel)

        val rightCenteringPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(rightPanel, BorderLayout.CENTER)
        }
        addToRight(rightCenteringPanel)
    }

    protected fun setRightPanelComponent(component: JComponent?) {
        if (component != null) {
            rightPanel.removeAll()
            rightPanel.add(component)
            rightPanel.revalidate()
            rightPanel.repaint()
        }
    }

    protected fun separator() = SeparatorComponent(
        ColorUtil.fromHex("#48494b"),
        SeparatorOrientation.VERTICAL
    ).apply {
        setVGap(4)
        setHGap(6)
    }

    private fun setupPanelAppearance() {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(ColorUtil.fromHex("#48494b"), 1, 1, 0, 1),
            JBUI.Borders.empty(4)
        )
        preferredSize = Dimension(preferredSize.width, 32)
        minimumSize = Dimension(preferredSize.width, 32)
    }

    private fun isProjectPath(path: String): Boolean {
        return config.project.basePath?.let {
            return path.startsWith(it)
        } ?: false
    }

    private fun createLeftPanel(virtualFile: VirtualFile?): JComponent {
        val filePath = config.filePath
        val linkOrLabel = when {
            filePath == null || !isProjectPath(filePath) -> createLanguageLabel()
            virtualFile == null -> createNewFileLink(filePath, config.editorEx)
            else -> createFileLinkPanel(virtualFile)
        }

        if (config.error != null) {
            errorLabel.isVisible = true
            errorLabel.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            ErrorPopoverHandler(config.project, errorLabel, config.error).install()

            return JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                isOpaque = false
                add(errorLabel)
                add(linkOrLabel)
            }
        }
        return linkOrLabel
    }

    private fun createFileLinkPanel(virtualFile: VirtualFile): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(ActionLink(virtualFile.name) {
                OpenFileAction.openFile(virtualFile, config.project)
            }.apply {
                setExternalLinkIcon()
            })
            add(statsComponent)
        }
    }

    private fun createNewFileLink(filePath: String, editor: EditorEx): ActionLink {
        var actionLink: ActionLink? = null
        actionLink = ActionLink("Add ${File(filePath).name}") {
            val file = File(filePath)
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            val content = editor.document.text
            try {
                val created = file.createNewFile()
                if (!created) {
                    return@ActionLink
                }
            } catch (ex: Exception) {
                logger.error(ex)
                return@ActionLink
            }

            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let { newFile ->
                runInEdt {
                    runUndoTransparentWriteAction {
                        newFile.writeText(content)
                    }

                    remove(actionLink)
                    addToLeft(createLeftPanel(newFile))

                    OpenFileAction.openFile(newFile, config.project)
                    ProjectView.getInstance(config.project).select(null, newFile, true)
                }
            }
        }.apply { icon = AllIcons.General.InlineAdd }
        return actionLink
    }

    private fun createLanguageLabel(): JBLabel {
        return JBLabel(config.language).apply {
            foreground = JBColor.GRAY
        }
    }
}
