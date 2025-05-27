package ee.carlrobert.codegpt.toolwindow.chat.editor.state

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment
import javax.swing.JComponent

interface EditorState {
    val editor: EditorEx
    val segment: Segment
    val project: Project

    fun updateContent(segment: Segment)
    fun createHeaderComponent(readOnly: Boolean): JComponent?
}
