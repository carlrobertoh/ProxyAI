package ee.carlrobert.codegpt.toolwindow.chat.editor.state

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DefaultHeaderPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.HeaderConfig
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment
import ee.carlrobert.codegpt.util.file.FileUtil
import javax.swing.JComponent

class RegularEditorState(
    override val editor: EditorEx,
    override val segment: Segment,
    override val project: Project
) : EditorState {

    override fun updateContent(segment: Segment) {
        runInEdt {
            runWriteAction {
                editor.document.setText(segment.content)
            }
        }
    }

    override fun createHeaderComponent(readOnly: Boolean): JComponent? {
        val languageMapping = FileUtil.findLanguageExtensionMapping(segment.language)
        return DefaultHeaderPanel(
            HeaderConfig(
                project,
                editor,
                segment.filePath,
                languageMapping.key,
                readOnly
            ),
        )
    }
}
