package ee.carlrobert.codegpt.actions.editor

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.inlineedit.InlineEditInlay
import javax.swing.Icon

open class InlineEditAction(icon: Icon) : BaseEditorAction(icon) {
    override fun actionPerformed(project: Project, editor: Editor, selectedText: String) {
        runInEdt {
            editor.getUserData(InlineEditInlay.INLAY_KEY)?.dispose()

            InlineEditInlay(editor).show()
        }
    }
}

class InlineEditFloatingMenuAction : InlineEditAction(Icons.DefaultSmall)

class InlineEditContextMenuAction : InlineEditAction(Icons.Sparkle)
