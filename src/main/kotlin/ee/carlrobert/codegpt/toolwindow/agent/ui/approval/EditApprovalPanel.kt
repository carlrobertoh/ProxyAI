package ee.carlrobert.codegpt.toolwindow.agent.ui.approval

import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.ide.actions.OpenFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.toolwindow.agent.ui.renderer.ChangeColors
import ee.carlrobert.codegpt.toolwindow.agent.ui.renderer.applyStringReplacement
import ee.carlrobert.codegpt.toolwindow.agent.ui.renderer.getFileContentWithFallback
import ee.carlrobert.codegpt.toolwindow.agent.ui.renderer.lineDiffStats
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel
import ee.carlrobert.codegpt.toolwindow.chat.parser.ReplaceWaiting
import java.awt.BorderLayout
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.JComponent
import ee.carlrobert.codegpt.toolwindow.chat.editor.factory.EditorFactory as ChatEditorFactory

class EditApprovalPanel(
    private val project: Project,
    private val request: ToolApprovalRequest,
    private val onApprove: (autoApproveSession: Boolean) -> Unit,
    private val onReject: () -> Unit
) : BorderLayoutPanel(), Disposable {
    private var diffCounts: Triple<Int, Int, Int>? = null // +ins, -del, ~changed

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

        val payload = request.payload as? EditPayload
        val filePath = payload?.filePath ?: ""
        val diffComponent = createInlineDiffComponent(payload)

        add(
            panel {
                row { label(request.title).bold() }

                row {
                    label(request.details).applyToComponent {
                        foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    }
                }

                if (filePath.isNotBlank()) {
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
                        diffCounts?.let { (ins, del, changed) ->
                            if (ins + del + changed > 0) {
                                cell(colorLabel("+${ins}", ChangeColors.inserted).apply {
                                    font = JBUI.Fonts.smallFont()
                                })
                                cell(colorLabel("-${del}", ChangeColors.deleted).apply {
                                    font = JBUI.Fonts.smallFont()
                                })
                                cell(colorLabel("~${changed}", ChangeColors.modified).apply {
                                    font = JBUI.Fonts.smallFont()
                                })
                            }
                        }
                    }
                }

                diffComponent?.let {
                    row {
                        cell(it).align(Align.FILL).resizableColumn()
                    }
                }

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
                }
            }.apply {
                border = JBUI.Borders.empty(4, 8)
            },
            BorderLayout.CENTER
        )
    }

    private fun createInlineDiffComponent(payload: EditPayload?): JComponent? {
        if (payload == null) return null
        val path = try {
            Paths.get(payload.filePath).normalize().toString()
        } catch (_: Exception) {
            payload.filePath
        }
        val vfs = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)

        val current = getFileContentWithFallback(path)
        val proposed = applyStringReplacement(
            current,
            payload.oldString,
            payload.newString,
            payload.replaceAll
        )

        val (insRaw, delRaw, changed) = lineDiffStats(current, proposed)
        diffCounts = Triple(insRaw, delRaw, changed)

        val language = vfs?.extension ?: "text"
        val segment = ReplaceWaiting(current, proposed, language, path)
        val editor = ChatEditorFactory.createEditor(project, segment)
        ResponseEditorPanel.RESPONSE_EDITOR_DIFF_VIEWER_KEY.get(editor)?.let { viewer ->
            val rightDoc = viewer.getDocument(Side.RIGHT)
            if (DiffUtil.executeWriteCommand(rightDoc, project, "Update proposed") {
                    rightDoc.setText(StringUtil.convertLineSeparators(proposed))
                    viewer.scheduleRediff()
                }) {
                viewer.rediff(true)
            }
        }
        ChatEditorFactory.configureEditor(editor, headerComponent = null)
        val comp = editor.component
        comp.border =
            JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
        comp.preferredSize = java.awt.Dimension(0, JBUI.scale(140))
        return comp
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

    override fun dispose() {}

    private fun extractBaseName(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val noSlash = path.substringAfterLast('/')
        return noSlash.substringAfterLast('\\')
    }

    private fun colorLabel(text: String, color: JBColor): JBLabel =
        JBLabel(text).apply { foreground = color }
}
