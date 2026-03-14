package ee.carlrobert.codegpt.diagnostics

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticsFilter(val displayName: String) {
    @SerialName("errors_only")
    ERRORS_ONLY("Errors only"),

    @SerialName("all")
    ALL("All");

    fun includes(severity: HighlightSeverity): Boolean {
        return when (this) {
            ERRORS_ONLY -> severity == HighlightSeverity.ERROR
            ALL -> severity == HighlightSeverity.ERROR ||
                    severity == HighlightSeverity.WARNING ||
                    severity == HighlightSeverity.WEAK_WARNING ||
                    severity == HighlightSeverity.INFORMATION
        }
    }

    fun minimumSeverity(): HighlightSeverity {
        return when (this) {
            ERRORS_ONLY -> HighlightSeverity.ERROR
            ALL -> HighlightSeverity.INFORMATION
        }
    }

    fun emptyMessage(): String {
        return when (this) {
            ERRORS_ONLY -> "No errors found."
            ALL -> "No diagnostics found."
        }
    }
}

data class DiagnosticsReport(
    val filePath: String,
    val filter: DiagnosticsFilter,
    val content: String = "",
    val diagnosticCount: Int = 0,
    val error: String? = null
) {
    val hasDiagnostics: Boolean
        get() = error == null && diagnosticCount > 0
}

@Service(Service.Level.PROJECT)
class ProjectDiagnosticsService(
    private val project: Project
) {

    fun findVirtualFile(filePath: String): VirtualFile? {
        val normalizedPath = filePath.replace('\\', '/')
        val fileSystem = LocalFileSystem.getInstance()
        return fileSystem.findFileByPath(normalizedPath)
            ?: fileSystem.refreshAndFindFileByPath(normalizedPath)
    }

    fun collect(
        virtualFile: VirtualFile,
        filter: DiagnosticsFilter = DiagnosticsFilter.ALL
    ): DiagnosticsReport {
        return try {
            var result = DiagnosticsReport(
                filePath = virtualFile.path,
                filter = filter
            )

            ApplicationManager.getApplication().invokeAndWait {
                result = ApplicationManager.getApplication().runWriteAction<DiagnosticsReport> {
                    DumbService.getInstance(project).runReadActionInSmartMode<DiagnosticsReport> {
                        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                            ?: return@runReadActionInSmartMode DiagnosticsReport(
                                filePath = virtualFile.path,
                                filter = filter,
                                error = "No document found for file."
                            )

                        PsiDocumentManager.getInstance(project).commitDocument(document)

                        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                            ?: return@runReadActionInSmartMode DiagnosticsReport(
                                filePath = virtualFile.path,
                                filter = filter,
                                error = "No PSI file found for: ${virtualFile.path}"
                            )

                        val rangeHighlights = DaemonCodeAnalyzerImpl.getHighlights(
                            document,
                            filter.minimumSeverity(),
                            project
                        )

                        val fileLevel = getFileLevelHighlights(psiFile)
                        val highlights = (rangeHighlights.asSequence() + fileLevel.asSequence())
                            .filter { filter.includes(it.severity) }
                            .mapNotNull { highlight ->
                                extractMessage(highlight)?.let { message ->
                                    DiagnosticEntry(highlight, message)
                                }
                            }
                            .distinctBy { Triple(it.message, it.highlight.startOffset, it.highlight.severity) }
                            .sortedWith(
                                compareBy<DiagnosticEntry>(
                                    { severityOrder(it.highlight.severity) },
                                    { it.highlight.startOffset.coerceAtLeast(0) }
                                )
                            )
                            .toList()

                        if (highlights.isEmpty()) {
                            return@runReadActionInSmartMode DiagnosticsReport(
                                filePath = virtualFile.path,
                                filter = filter
                            )
                        }

                        val maxItems = 200
                        val overflow = (highlights.size - maxItems).coerceAtLeast(0)
                        val shown = highlights.take(maxItems)

                        val content = buildString {
                            append("File: ${virtualFile.name}\n")
                            append("Path: ${virtualFile.path}\n")
                            append("Filter: ${filter.displayName}\n\n")

                            shown.forEach { entry ->
                                val info = entry.highlight
                                val startOffset = info.startOffset.coerceIn(0, document.textLength)
                                val lineColText =
                                    if (info.startOffset >= 0 && document.textLength > 0) {
                                        val line = document.getLineNumber(startOffset) + 1
                                        val col =
                                            startOffset - document.getLineStartOffset(line - 1) + 1
                                        "line $line, col $col"
                                    } else {
                                        "file-level"
                                    }

                                append("- [${severityLabel(info.severity)}] $lineColText: ${entry.message}\n")
                            }

                            if (overflow > 0) {
                                append("... ($overflow more not shown)\n")
                            }
                        }

                        DiagnosticsReport(
                            filePath = virtualFile.path,
                            filter = filter,
                            content = content,
                            diagnosticCount = highlights.size
                        )
                    }
                }
            }

            result
        } catch (e: Exception) {
            DiagnosticsReport(
                filePath = virtualFile.path,
                filter = filter,
                error = "Error retrieving diagnostics: ${e.message}"
            )
        }
    }

    private fun getFileLevelHighlights(psiFile: com.intellij.psi.PsiFile): List<HighlightInfo> {
        return try {
            val method = DaemonCodeAnalyzerImpl::class.java.methods.firstOrNull {
                it.name == "getFileLevelHighlights" && it.parameterCount == 2
            }
            if (method != null) {
                @Suppress("UNCHECKED_CAST")
                method.invoke(null, project, psiFile) as? List<HighlightInfo> ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun extractMessage(info: HighlightInfo): String? {
        val rawMessage = info.description ?: info.toolTip ?: ""
        return StringUtil.removeHtmlTags(rawMessage, false)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun severityLabel(severity: HighlightSeverity): String {
        return when (severity) {
            HighlightSeverity.ERROR -> "ERROR"
            HighlightSeverity.WARNING -> "WARNING"
            HighlightSeverity.WEAK_WARNING -> "WEAK_WARNING"
            HighlightSeverity.INFORMATION -> "INFO"
            else -> severity.toString()
        }
    }

    private fun severityOrder(severity: HighlightSeverity): Int {
        return when (severity) {
            HighlightSeverity.ERROR -> 0
            HighlightSeverity.WARNING -> 1
            HighlightSeverity.WEAK_WARNING -> 2
            HighlightSeverity.INFORMATION -> 3
            else -> 4
        }
    }

    private data class DiagnosticEntry(
        val highlight: HighlightInfo,
        val message: String
    )
}
