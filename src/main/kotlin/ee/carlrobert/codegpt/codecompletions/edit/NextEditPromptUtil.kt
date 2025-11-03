package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import ee.carlrobert.codegpt.util.RecentlyViewedFilesUtil
import kotlin.math.min

object NextEditPromptUtil {

    fun takeLineWindow(content: String?, maxLines: Int): String {
        if (content.isNullOrEmpty()) return ""
        val lines = content.split('\n')
        if (lines.size <= maxLines) return content

        val center = lines.size / 2
        val half = maxLines / 2
        var start = (center - half).coerceAtLeast(0)
        var end = (start + maxLines).coerceAtMost(lines.size)
        start = start.coerceAtMost(end - maxLines).coerceAtLeast(0)

        val sb = StringBuilder()
        for (i in start until end) {
            sb.append(lines[i])
            if (i < end - 1) sb.append('\n')
        }
        return sb.toString()
    }

    fun determineEditableRegionByLines(
        code: String?,
        cursorOffset: Int,
        linesBefore: Int,
        linesAfter: Int,
        maxLines: Int
    ): Pair<Int, Int> {
        if (code == null) return 0 to 0
        val len = code.length
        val cursor = cursorOffset.coerceIn(0, len)

        val lineStarts = mutableListOf<Int>()
        lineStarts.add(0)
        var i = 0
        while (i < len) {
            if (code[i] == '\n') {
                if (i + 1 < len) lineStarts.add(i + 1)
            }
            i++
        }
        val totalLines = lineStarts.size

        var cursorLine = 0
        for (idx in 0 until totalLines) {
            val start = lineStarts[idx]
            val nextStart = if (idx + 1 < totalLines) lineStarts[idx + 1] else len + 1
            if (cursor >= start && cursor < nextStart) {
                cursorLine = idx
                break
            }
        }

        var startLine = (cursorLine - linesBefore).coerceAtLeast(0)
        var endLine = (cursorLine + linesAfter).coerceAtMost(totalLines - 1)

        val desired = endLine - startLine + 1
        if (desired > maxLines) {
            val keepAfter =
                linesAfter.coerceAtMost(maxLines - 1 - min(linesBefore, cursorLine))
            val keepBefore = maxLines - 1 - keepAfter
            startLine = (cursorLine - keepBefore).coerceAtLeast(0)
            endLine = (cursorLine + keepAfter).coerceAtMost(totalLines - 1)
        } else if (desired < maxLines) {
            val extra = maxLines - desired
            val expandBefore = min(extra / 2, startLine)
            val expandAfter = min(extra - expandBefore, (totalLines - 1) - endLine)
            startLine -= expandBefore
            endLine += expandAfter
        }

        val startPos = lineStarts[startLine]
        val endPos = if (endLine + 1 < totalLines) lineStarts[endLine + 1] else len
        return startPos to endPos.coerceAtLeast(startPos)
    }

    fun buildRecentlyViewedBlock(
        project: Project?,
        currentFilePath: String?,
        maxSnippets: Int,
        snippetLines: Int
    ): String {
        val sb = StringBuilder()
        sb.append("<|recently_viewed_code_snippets|>\n")
        if (project != null) {
            val currentVf =
                currentFilePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            val files = RecentlyViewedFilesUtil.orderedFiles(project, currentVf, maxSnippets)
            var count = 0
            for (vf in files) {
                if (count >= maxSnippets) break
                val path = vf.path
                val content = runReadAction {
                    FileDocumentManager.getInstance().getDocument(vf)?.text
                        ?: runCatching { VfsUtilCore.loadText(vf) }.getOrNull()
                } ?: ""
                val snippet = takeLineWindow(content, snippetLines)
                sb.append("<|recently_viewed_code_snippet|>\n")
                sb.append("code_snippet_file_path: ").append(path).append('\n')
                sb.append(snippet)
                if (!snippet.endsWith('\n')) sb.append('\n')
                sb.append("<|/recently_viewed_code_snippet|>\n\n")
                count++
            }
        }
        sb.append("<|/recently_viewed_code_snippets|>\n\n")
        return sb.toString()
    }
}

