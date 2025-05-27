package ee.carlrobert.codegpt.toolwindow.chat.editor.header

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.OpenFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.SeparatorComponent
import com.intellij.ui.SeparatorOrientation
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
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
    val loading: Boolean = false
)

abstract class HeaderPanel(protected val config: HeaderConfig) : BorderLayoutPanel() {

    companion object {
        private val logger = thisLogger()
    }

    protected var virtualFile: VirtualFile? = config.filePath?.let {
        try {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(it))
        } catch (t: Throwable) {
            logger.error(t)
            null
        }
    }

    private val rightPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }

    protected abstract fun initializeRightPanel(rightPanel: JPanel)

    protected fun setupUI() {
        setupPanelAppearance()
        setupFilePathOrLanguageLabel(virtualFile)
        rightPanel.removeAll()
        initializeRightPanel(rightPanel)
        addToRight(rightPanel)
    }

    protected fun setRightPanelComponent(component: JComponent?) {
        if (component != null) {
            rightPanel.removeAll()
            rightPanel.add(component)
            revalidate()
            repaint()
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
    }

    protected fun setupFilePathOrLanguageLabel(virtualFile: VirtualFile?) {
        val filePath = config.filePath
        if (filePath != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                if (virtualFile == null) {
                    addComponent(createNewFileLink(filePath, config.editorEx))
                } else {
                    virtualFile.refresh(true, false)
                    addComponent(createFileLink(virtualFile))
                }
            }
        } else {
            addComponent(createLanguageLabel())
        }
    }

    private fun addComponent(component: JComponent) {
        runInEdt {
            addToLeft(component)
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
                file.writeText(content)
            } catch (ex: Exception) {
                logger.error(ex)
                return@ActionLink
            }

            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let { newFile ->
                runInEdt {
                    OpenFileAction.openFile(newFile, config.project)
                    remove(actionLink)
                    setupFilePathOrLanguageLabel(newFile)
                }
            }
        }.apply { icon = AllIcons.General.InlineAdd }
        return actionLink
    }

    private fun createFileLink(virtualFile: VirtualFile): ActionLink {
        return ActionLink(virtualFile.name) {
            OpenFileAction.openFile(virtualFile, config.project)
        }.apply { setExternalLinkIcon() }
    }

    private fun createLanguageLabel(): JBLabel {
        return JBLabel(config.language).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyLeft(4)
        }
    }
}
