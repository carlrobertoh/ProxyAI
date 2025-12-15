package ee.carlrobert.codegpt.toolwindow.agent.ui.approval

import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.toolwindow.chat.parser.Code
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JComponent
import ee.carlrobert.codegpt.toolwindow.chat.editor.factory.EditorFactory as ChatEditorFactory

class BashApprovalPanel(
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

        val payload = request.payload as? BashPayload
        val editorComponent = createEditorComponent(payload?.command ?: request.details)

        add(
            panel {
                row {
                    label(request.title).bold().applyToComponent {
                        border = JBUI.Borders.emptyLeft(6)
                    }
                }

                row {
                    cell(editorComponent).align(Align.FILL).resizableColumn()
                }.topGap(TopGap.SMALL)

                payload?.description?.let {
                    row {
                        comment(it).applyToComponent {
                            border = JBUI.Borders.emptyLeft(6)
                        }
                    }
                }

                row {
                    link("Run") { approve(false) }.applyToComponent {
                        border = JBUI.Borders.emptyLeft(6)
                    }
                    text("|").applyToComponent {
                        foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    }
                    link("Always run for this session") { approve(true) }
                    text("|").applyToComponent {
                        foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    }
                    link("Reject") { reject() }
                }
            },
            BorderLayout.CENTER
        )
    }

    private fun createEditorComponent(command: String): JComponent {
        val segment = Code(content = command, language = "bash", filePath = "temp.bash")
        val editor = ChatEditorFactory.createEditor(project, segment)
        ChatEditorFactory.configureEditor(editor, headerComponent = null)
        return editor.component.apply {
            border =
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
            preferredSize = java.awt.Dimension(0, JBUI.scale(80))
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
}