package ee.carlrobert.codegpt.toolwindow.chat.editor.diff

import com.intellij.diff.tools.fragmented.UnifiedDiffChange
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diff.DiffBundle
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.intellij.util.application

class DiffEditorManager(
    private val project: Project,
    private val diffViewer: UnifiedDiffViewer,
    private val virtualFile: VirtualFile?
) {

    private val operations = mutableMapOf<String, String>()

    fun updateDiffContent(searchContent: String, replaceContent: String) {
        val originalText = virtualFile?.readText() ?: return
        val document = diffViewer.getDocument(Side.RIGHT)

        operations[searchContent.trim()] = replaceContent.trim()

        var resultText = originalText
        for ((search, replace) in operations) {
            resultText = resultText.replace(search, replace)
        }

        application.executeOnPooledThread {
            runInEdt {
                if (DiffUtil.executeWriteCommand(document, project, "Updating document") {
                        document.setText(StringUtil.convertLineSeparators(resultText))
                        diffViewer.scheduleRediff()
                    }) {
                    diffViewer.rediff()
                    scrollToLastChange(diffViewer)
                }
            }
        }
    }

    fun applyAllChanges(): List<UnifiedDiffChange> {
        val document = diffViewer.getDocument(Side.LEFT)
        val allChanges = mutableListOf<UnifiedDiffChange>()

        while (true) {
            val changes = diffViewer.diffChanges ?: break
            if (changes.isEmpty()) break

            val change = changes.first()

            DiffUtil.executeWriteCommand(
                document,
                project,
                DiffBundle.message("message.replace.change.command")
            ) {
                diffViewer.replaceChange(change, Side.RIGHT)
                diffViewer.scheduleRediff()
            }
            diffViewer.rediff(true)

            allChanges.add(change)
        }

        return allChanges
    }

    private fun scrollToLastChange(viewer: UnifiedDiffViewer) {
        val change = viewer.diffChanges?.lastOrNull() ?: return
        viewer.editors.firstOrNull()?.scrollingModel?.scrollTo(
            LogicalPosition(change.lineFragment.startLine2, 0),
            ScrollType.CENTER
        )
    }
}