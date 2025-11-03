package ee.carlrobert.codegpt.inlineedit

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import java.awt.AWTEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * Editor-scoped key dispatcher to reliably intercept Cmd/Ctrl+Y and Cmd/Ctrl+N
 * for Inline Edit while the session is active and the editor has focus.
 */
class InlineEditKeyEventDispatcher(
    private val project: Project,
    private val editor: EditorEx,
    private val onAccept: () -> Unit,
    private val onReject: () -> Unit,
) : IdeEventQueue.EventDispatcher, Disposable {

    override fun dispatch(e: AWTEvent): Boolean {
        if (e !is KeyEvent) return false
        if (e.id != KeyEvent.KEY_PRESSED) return false

        val selected = FileEditorManager.getInstance(project).selectedTextEditor
        if (selected !== editor) return false

        val ks = KeyStroke.getKeyStrokeForEvent(e)
        val (acceptKeys, rejectKeys) = currentInlineEditKeystrokes()
        if (rejectKeys.contains(ks)) { onReject(); e.consume(); return true }
        if (acceptKeys.contains(ks)) { onAccept(); e.consume(); return true }
        return false
    }

    fun register(parent: Disposable) {
        IdeEventQueue.Companion.getInstance().addDispatcher(this, parent)
    }

    override fun dispose() {
    }

    private fun currentInlineEditKeystrokes(): Pair<Set<KeyStroke>, Set<KeyStroke>> {
        val km = KeymapManager.getInstance().activeKeymap
        fun firstKeyStrokes(actionId: String): Set<KeyStroke> =
            km.getShortcuts(actionId)
                .mapNotNull { (it as? KeyboardShortcut)?.firstKeyStroke }
                .toSet()

        val accept = firstKeyStrokes("CodeGPT.AcceptCurrentInlineEdit")
        val reject = firstKeyStrokes("CodeGPT.RejectCurrentInlineEdit")
        return Pair(accept, reject)
    }
}
