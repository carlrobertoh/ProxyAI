package ee.carlrobert.codegpt.actions

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.ui.textarea.header.tag.SingleDiagnosticTagDetails

class AddProblemToContextAction : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = extractProblemContext(project, e) ?: run {
            OverlayUtil.showNotification(
                "No problem selected. Open the Problems tool window, select a row, then press the shortcut.",
                NotificationType.INFORMATION
            )
            return
        }

        val tag = SingleDiagnosticTagDetails(
            virtualFile = context.virtualFile,
            line = context.line,
            column = context.column,
            severityName = context.severityName,
            description = context.description
        )

        val contentManager = project.service<ChatToolWindowContentManager>()
        contentManager.displayChatTab()
        val chatTabPanel = contentManager.tryFindActiveChatTabPanel()
            .orElseGet { contentManager.createNewTabPanel() }
        chatTabPanel.addContextTag(tag)
    }

    private data class ProblemContext(
        val virtualFile: VirtualFile,
        val line: Int,
        val column: Int,
        val severityName: String,
        val description: String,
    )

    private fun extractProblemContext(project: Project, e: AnActionEvent): ProblemContext? {
        val problemContext = readFromProblemNavigatable(project, e)
        if (problemContext != null) return problemContext

        val descriptor = e.getData(CommonDataKeys.NAVIGATABLE) as? OpenFileDescriptor
        if (descriptor != null) {
            return readFromOpenFileDescriptor(project, descriptor)
        }

        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return readFirstHighlight(project, virtualFile, offsetHint = -1, lineHint = -1)
    }

    private fun readFromProblemNavigatable(project: Project, e: AnActionEvent): ProblemContext? {
        val problemClass = try {
            Class.forName("com.intellij.analysis.problemsView.Problem")
        } catch (_: ClassNotFoundException) {
            return null
        }

        val navigatable: Navigatable = e.getData(CommonDataKeys.NAVIGATABLE) ?: return null
        if (!problemClass.isInstance(navigatable)) return null

        val virtualFile = problemClass.invokeNullable<VirtualFile>(navigatable, "getFile") ?: return null
        val rawLine = problemClass.invokeNullable<Int>(navigatable, "getLine") ?: -1
        val rawColumn = problemClass.invokeNullable<Int>(navigatable, "getColumn") ?: -1
        val rawOffset = problemClass.invokeNullable<Int>(navigatable, "getOffset") ?: -1
        val description = problemClass.invokeNullable<String>(navigatable, "getDescription")
        val text = problemClass.invokeNullable<String>(navigatable, "getText").orEmpty()

        val displayDescription = StringUtil.removeHtmlTags(
            (description?.takeIf { it.isNotBlank() } ?: text).trim(),
            false
        )

        val line = if (rawLine >= 0) rawLine + 1 else -1
        val column = if (rawColumn >= 0) rawColumn + 1 else -1

        val severityName = resolveSeverityName(project, virtualFile, rawOffset, rawLine)

        return ProblemContext(
            virtualFile = virtualFile,
            line = line,
            column = column,
            severityName = severityName,
            description = displayDescription
        )
    }

    private fun readFromOpenFileDescriptor(
        project: Project,
        descriptor: OpenFileDescriptor
    ): ProblemContext? {
        return readFirstHighlight(
            project,
            descriptor.file,
            offsetHint = descriptor.offset,
            lineHint = descriptor.line
        )
    }

    private fun readFirstHighlight(
        project: Project,
        virtualFile: VirtualFile,
        offsetHint: Int,
        lineHint: Int
    ): ProblemContext? {
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val info = findBestHighlight(project, virtualFile, document, offsetHint, lineHint)
            ?: return null

        val startOffset = info.startOffset.coerceIn(0, document.textLength)
        val line = if (document.textLength > 0) document.getLineNumber(startOffset) + 1 else -1
        val column = if (line > 0) {
            startOffset - document.getLineStartOffset(line - 1) + 1
        } else {
            -1
        }
        val rawMessage = info.description ?: info.toolTip ?: ""
        val description = StringUtil.removeHtmlTags(rawMessage, false).trim()

        return ProblemContext(
            virtualFile = virtualFile,
            line = line,
            column = column,
            severityName = severityNameOf(info.severity),
            description = description.ifBlank { "(no description)" }
        )
    }

    private fun resolveSeverityName(
        project: Project,
        virtualFile: VirtualFile,
        offsetHint: Int,
        lineHint: Int
    ): String {
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: return "ERROR"
        val info = findBestHighlight(project, virtualFile, document, offsetHint, lineHint)
            ?: return "ERROR"
        return severityNameOf(info.severity)
    }

    private fun findBestHighlight(
        project: Project,
        virtualFile: VirtualFile,
        document: com.intellij.openapi.editor.Document,
        offsetHint: Int,
        lineHint: Int
    ): HighlightInfo? {
        var best: HighlightInfo? = null
        runReadAction {
            DaemonCodeAnalyzerEx.processHighlights(
                document,
                project,
                HighlightSeverity.INFORMATION,
                0,
                document.textLength
            ) { info ->
                val matches = when {
                    offsetHint >= 0 && offsetHint in info.startOffset..info.endOffset -> true
                    lineHint >= 0 && document.textLength > 0 && info.startOffset >= 0 -> {
                        val lineOfInfo = document.getLineNumber(
                            info.startOffset.coerceIn(0, document.textLength)
                        )
                        lineOfInfo == lineHint
                    }
                    else -> false
                }
                if (matches) {
                    val current = best
                    if (current == null || info.severity.compareTo(current.severity) > 0) {
                        best = info
                    }
                }
                true
            }
        }
        return best
    }

    private fun severityNameOf(severity: HighlightSeverity): String {
        return when (severity) {
            HighlightSeverity.ERROR -> "ERROR"
            HighlightSeverity.WARNING -> "WARNING"
            HighlightSeverity.WEAK_WARNING -> "WEAK_WARNING"
            HighlightSeverity.INFORMATION -> "INFO"
            else -> severity.toString()
        }
    }

    private inline fun <reified T> Class<*>.invokeNullable(target: Any, methodName: String): T? {
        return try {
            val value = getMethod(methodName).invoke(target)
            value as? T
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
