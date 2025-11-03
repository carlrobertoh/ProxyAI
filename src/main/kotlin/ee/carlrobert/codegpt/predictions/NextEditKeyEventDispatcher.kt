package ee.carlrobert.codegpt.predictions

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.keymap.KeymapManager
import java.awt.AWTEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

class NextEditKeyEventDispatcher(
    private val editor: Editor,
    private val isActive: () -> Boolean,
    private val onEscape: () -> Unit,
    private val onTab: () -> Unit,
    private val onDisposeForEdit: () -> Unit,
) : IdeEventQueue.EventDispatcher, Disposable {
    fun register(parent: Disposable) {
        IdeEventQueue.getInstance().addDispatcher(this, parent)
    }

    override fun dispatch(e: AWTEvent): Boolean {
        if (e !is KeyEvent) return false
        val project = editor.project ?: return false
        val selected = FileEditorManager.getInstance(project).selectedTextEditor
        if (selected !== editor) return false

        if (e.id == KeyEvent.KEY_TYPED) {
            if (!isActive()) return false
            if (!editor.contentComponent.isFocusOwner) return false
            val ch = e.keyChar
            val printable = ch.code >= 32 && !Character.isISOControl(ch)
            val hasCmd =
                (e.modifiersEx and (KeyEvent.META_DOWN_MASK or KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK)) != 0
            if (printable && !hasCmd) onDisposeForEdit()
            return false
        }
        if (e.id != KeyEvent.KEY_PRESSED) return false

        val ks = KeyStroke.getKeyStrokeForEvent(e)
        if (ks.keyCode == KeyEvent.VK_ESCAPE && ks.modifiers == 0) {
            if (!isActive()) return false
            onEscape(); e.consume(); return true
        }
        if (ks.keyCode == KeyEvent.VK_TAB && ks.modifiers == 0) {
            if (!isActive()) return false
            onTab(); e.consume(); return true
        }
        if (currentDisposeKeystrokes().contains(ks)) {
            if (!isActive()) return false
            onDisposeForEdit(); return false
        }
        return false
    }

    private fun currentDisposeKeystrokes(): Set<KeyStroke> {
        val km = try {
            KeymapManager.getInstance().activeKeymap
        } catch (_: Exception) {
            null
        }

        fun firstKeyStrokes(actionId: String) = try {
            km?.getShortcuts(actionId)?.mapNotNull { (it as? KeyboardShortcut)?.firstKeyStroke }
                ?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }

        val fromActions = buildSet {
            addAll(firstKeyStrokes("Undo")); addAll(firstKeyStrokes("Redo"))
            addAll(firstKeyStrokes("EditorUndo")); addAll(firstKeyStrokes("EditorRedo"))
            addAll(firstKeyStrokes("EditorCut")); addAll(firstKeyStrokes("EditorPaste"))
            addAll(firstKeyStrokes("EditorEnter")); addAll(firstKeyStrokes("StartNewLine"))
        }
        val fallbacks = setOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.META_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(
                KeyEvent.VK_Z,
                KeyEvent.META_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK
            ),
            KeyStroke.getKeyStroke(
                KeyEvent.VK_Z,
                KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK
            ),
            KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.META_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.META_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0),
            KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
        )
        return fromActions + fallbacks
    }

    override fun dispose() {}
}
