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
import ee.carlrobert.codegpt.toolwindow.agent.ui.renderer.*
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel
import ee.carlrobert.codegpt.toolwindow.chat.parser.ReplaceWaiting
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
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

        val filePath = when (val payload = request.payload) {
            is EditPayload -> payload.filePath
            else -> ""
        }
        val diffComponent = createInlineDiffComponent()

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
                                cell(compactDiffPanel(ins, del, changed))
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
                    label(" | ").applyToComponent {
                        foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    }
                    link("Always accept for this session") { approve(true) }
                    label(" | ").applyToComponent {
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

    private fun createInlineDiffComponent(): JComponent? {
        val payload = request.payload ?: return null

        val (path, current, proposed) = when (payload) {
            is EditPayload -> {
                val normalizedPath = try {
                    Paths.get(payload.filePath).normalize().toString()
                } catch (_: Exception) {
                    payload.filePath
                }
                val currentContent = getFileContentWithFallback(normalizedPath)
                val proposedContent = if (!payload.proposedContent.isNullOrBlank()) {
                    payload.proposedContent
                } else {
                    applyStringReplacement(
                        currentContent,
                        payload.oldString,
                        payload.newString,
                        payload.replaceAll
                    )
                }
                Triple(normalizedPath, currentContent, proposedContent)
            }

            else -> return null
        }

        val (insRaw, delRaw, changed) = lineDiffStats(current, proposed)
        diffCounts = Triple(insRaw, delRaw, changed)

        val vfs = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
        val language = vfs?.extension ?: "text"
        return buildDiffEditor(current, proposed, language, path)
    }

    private fun buildDiffEditor(
        current: String,
        proposed: String,
        language: String,
        path: String
    ): JComponent {
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

    private fun compactDiffPanel(inserted: Int, deleted: Int, changed: Int): JComponent {
        val texts = diffBadgeText(inserted, deleted, changed)
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(colorLabel(texts.inserted, ChangeColors.inserted).apply {
                font = JBUI.Fonts.smallFont()
            })
            add(colorLabel(texts.deleted, ChangeColors.deleted).apply {
                font = JBUI.Fonts.smallFont()
            })
            add(colorLabel(texts.changed, ChangeColors.modified).apply {
                font = JBUI.Fonts.smallFont()
            })
        }
    }
}
