package ee.carlrobert.codegpt.ui.components

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import java.awt.Color
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

object InlineEditChips {

    val GREEN = JBColor(Color(0x00, 0x99, 0x00), Color(0x00, 0x99, 0x00))
    val RED = JBColor(Color(0xD0, 0x36, 0x36), Color(0xD0, 0x36, 0x36))
    val TEXT = JBColor(Color(0xDF, 0xE1, 0xE5), Color(0xDF, 0xE1, 0xE5))

    fun keyY(onClick: () -> Unit) = BadgeChip(
        currentShortcutLabel(
            actionId = "CodeGPT.AcceptCurrentInlineEdit",
            preferredKeyCode = KeyEvent.VK_ENTER,
            macFallback = "⌘Enter",
            otherFallback = "Ctrl+Enter"
        ),
        GREEN,
        onClick,
        textColor = TEXT
    )

    fun keyN(onClick: () -> Unit) = BadgeChip(
        currentShortcutLabel(
            actionId = "CodeGPT.RejectCurrentInlineEdit",
            preferredKeyCode = KeyEvent.VK_BACK_SPACE,
            macFallback = "⌘Backspace",
            otherFallback = "Ctrl+Backspace"
        ),
        RED,
        onClick,
        textColor = TEXT
    )

    private fun currentShortcutLabel(
        actionId: String,
        preferredKeyCode: Int,
        macFallback: String,
        otherFallback: String
    ): String {
        val fb = if (SystemInfo.isMac) macFallback else otherFallback
        return try {
            val keymap = KeymapManager.getInstance().activeKeymap
            val shortcuts = keymap.getShortcuts(actionId)
            val preferred = preferredShortcut(shortcuts, preferredKeyCode)
                ?: shortcuts.firstOrNull() as? KeyboardShortcut
            if (preferred != null) keyStrokeToLabel(preferred.firstKeyStroke) else fb
        } catch (_: Exception) {
            fb
        }
    }

    private fun preferredShortcut(
        shortcuts: Array<com.intellij.openapi.actionSystem.Shortcut>,
        keyCode: Int
    ): KeyboardShortcut? {
        val macKs = KeyStroke.getKeyStroke(keyCode, InputEvent.META_DOWN_MASK)
        val winKs = KeyStroke.getKeyStroke(keyCode, InputEvent.CTRL_DOWN_MASK)
        return shortcuts.firstOrNull { (it as? KeyboardShortcut)?.firstKeyStroke == macKs } as? KeyboardShortcut
            ?: shortcuts.firstOrNull { (it as? KeyboardShortcut)?.firstKeyStroke == winKs } as? KeyboardShortcut
    }

    private fun keyStrokeToLabel(ks: KeyStroke): String {
        return if (SystemInfo.isMac) {
            buildString {
                if (ks.modifiers and InputEvent.META_DOWN_MASK != 0) append('⌘')
                if (ks.modifiers and InputEvent.SHIFT_DOWN_MASK != 0) append('⇧')
                if (ks.modifiers and InputEvent.ALT_DOWN_MASK != 0) append('⌥')
                if (ks.modifiers and InputEvent.CTRL_DOWN_MASK != 0) append('⌃')
                append(KeyEvent.getKeyText(ks.keyCode).uppercase())
            }
        } else {
            val parts = mutableListOf<String>()
            if (ks.modifiers and InputEvent.CTRL_DOWN_MASK != 0) parts.add("Ctrl")
            if (ks.modifiers and InputEvent.SHIFT_DOWN_MASK != 0) parts.add("Shift")
            if (ks.modifiers and InputEvent.ALT_DOWN_MASK != 0) parts.add("Alt")
            if (ks.modifiers and InputEvent.META_DOWN_MASK != 0) parts.add("Meta")
            parts.add(KeyEvent.getKeyText(ks.keyCode).uppercase())
            parts.joinToString("+")
        }
    }

    fun acceptAll(onClick: () -> Unit) =
        BadgeChip(CodeGPTBundle.get("shared.acceptAll"), GREEN, onClick, textColor = TEXT)

    fun rejectAll(onClick: () -> Unit) =
        BadgeChip(CodeGPTBundle.get("shared.rejectAll"), RED, onClick, textColor = TEXT)
}
