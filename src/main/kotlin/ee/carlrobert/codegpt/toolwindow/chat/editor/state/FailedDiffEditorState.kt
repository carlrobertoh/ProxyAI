package ee.carlrobert.codegpt.toolwindow.chat.editor.state

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DefaultHeaderPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.HeaderConfig
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment
import javax.swing.JComponent

class FailedDiffEditorState(
    override val editor: EditorEx,
    override val segment: Segment,
    override val project: Project,
    private val searchContent: String,
    private val replaceContent: String
) : EditorState {

    override fun updateContent(segment: Segment) {
        runInEdt {
            runWriteAction {
                editor.document.setText(segment.content)
            }
        }
    }

    override fun createHeaderComponent(readOnly: Boolean): JComponent? {
        val filePath = segment.filePath
        val extension = filePath?.substringAfterLast('.', "txt") ?: "txt"

        return DefaultHeaderPanel(HeaderConfig(project, editor, filePath, extension, false))
    }
}
