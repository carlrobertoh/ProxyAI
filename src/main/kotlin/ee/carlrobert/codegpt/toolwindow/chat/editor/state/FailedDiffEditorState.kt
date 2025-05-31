package ee.carlrobert.codegpt.toolwindow.chat.editor.state

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
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

        val content = """
            <html>
            <body>
            <div class='content'>
              <p>The model-generated <code>SEARCH</code> block could not be mapped to any existing code in your file.</p>
              
              <div style="margin-bottom: 4px;"><b>Failed search block:</b></div>
              <div class="code-block">
                <pre style="padding: 0px; margin: 0px"><span style="">$searchContent</span></pre>
              </div>
              <p>
                Tips to consider:
              </p>
              <ul style="padding-left: 4px;">
                <li>Use the best performing model available to you.</li>
                <li>Keep the context window small - avoid large files and conversations.</li>
                <li>Give clear, step-by-step instructions for your request.</li>
                <li>Specify which updates are already applied and which are outstanding.</li>
              </ul>
            </div>
            <table class='sections'>
              <tr>
                <td valign='top' class='section'><p>See also:</td>
                <td valign='top'>
                  <p><a href="https://docs.tryproxy.io/#multi-file-edits">https://docs.tryproxy.io/#multi-file-edits</a></p>
                </td>
            </table>
            </body>
            </html>
                    """.trimIndent()

        return DefaultHeaderPanel(HeaderConfig(project, editor, filePath, extension, false, content))
    }
}
