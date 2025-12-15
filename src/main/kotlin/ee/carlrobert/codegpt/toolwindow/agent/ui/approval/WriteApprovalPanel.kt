package ee.carlrobert.codegpt.toolwindow.agent.ui.approval

import com.intellij.ide.actions.OpenFileAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.toolwindow.chat.parser.Code
import javax.swing.BorderFactory
import javax.swing.JComponent
import ee.carlrobert.codegpt.toolwindow.chat.editor.factory.EditorFactory as ChatEditorFactory

class WriteApprovalPanel(
    private val project: Project,
    private val request: ToolApprovalRequest,
    private val onApprove: (autoApproveSession: Boolean) -> Unit,
    private val onReject: () -> Unit
) : BorderLayoutPanel() {
    init {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(2, 0),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
                    1
                ),
                JBUI.Borders.empty(1)
            )
        )

        val payload = request.payload as? WritePayload
        val filePath = payload?.filePath
        val content = payload?.content
        val editorComponent = createEditorComponent(filePath, content)
        val metrics = content?.let { "${it.length} bytes • ${it.lines().size} lines" }

        addToCenter(panel {
            row { label(request.title).bold() }

            if (!filePath.isNullOrBlank()) {
                row("File:") {
                    val baseName = extractBaseName(filePath)
                    val link = ActionLink(baseName) {
                        LocalFileSystem.getInstance().findFileByPath(filePath)
                            ?.let { OpenFileAction.openFile(it, project) }
                    }.apply {
                        toolTipText = filePath
                        setExternalLinkIcon()
                    }
                    cell(link).gap(RightGap.SMALL)
                }
            }

            if (!metrics.isNullOrBlank()) row { comment(metrics) }

            if (editorComponent != null) row {
                cell(editorComponent).align(Align.FILL).resizableColumn()
            }.topGap(
                TopGap.SMALL
            )

            row {
                link("Accept") { approve(false) }
                label("|").applyToComponent {
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                }
                link("Always accept for this session") { approve(true) }
                label("|").applyToComponent {
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                }
                link("Reject") { reject() }
            }.topGap(TopGap.SMALL)
        })
    }

    private fun createEditorComponent(filePath: String?, content: String?): JComponent? {
        if (content == null) return null
        val language = filePath?.substringAfterLast('.', missingDelimiterValue = "text") ?: "text"
        val segment = Code(content = content, language = language, filePath = filePath)
        val editor = ChatEditorFactory.createEditor(project, segment)
        ChatEditorFactory.configureEditor(editor, headerComponent = null)
        return editor.component.apply {
            border =
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
            preferredSize = java.awt.Dimension(0, JBUI.scale(140))
        }
    }

    private fun approve(auto: Boolean) {
        onApprove(auto)
        removeSelf()
    }

    private fun reject() {
        onReject()
        removeSelf()
    }

    private fun removeSelf() {
        isVisible = false
        parent?.remove(this)
        parent?.revalidate()
        parent?.repaint()
    }

    private fun extractBaseName(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val noSlash = path.substringAfterLast('/')
        return noSlash.substringAfterLast('\\')
    }
}
