package ee.carlrobert.codegpt.ui.textarea

import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.util.TextRange
import com.intellij.ui.ComponentUtil.findParentByCondition
import java.awt.AWTEvent
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.*

class PromptTextFieldEventDispatcher(
    private val dispatcherId: UUID,
    private val onBackSpace: () -> Unit,
    private val lookup: LookupImpl?,
    private val onSubmit: (KeyEvent) -> Unit
) : IdeEventQueue.EventDispatcher {

    override fun dispatch(e: AWTEvent): Boolean {
        if ((e is KeyEvent || e is MouseEvent) && findParent() is PromptTextField) {
            if (e is KeyEvent) {
                if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_BACK_SPACE) {
                    onBackSpace()
                }

                if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown) {
                        handleShiftEnter(e)
                    } else if (e.modifiersEx and InputEvent.ALT_DOWN_MASK == 0
                        && e.modifiersEx and InputEvent.CTRL_DOWN_MASK == 0
                    ) {
                        onSubmit(e)
                    }
                }

                return e.isConsumed
            }
        }
        return false
    }

    private fun findParent(): Component? {
        return findParentByCondition(KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner) { component ->
            component is PromptTextField && component.dispatcherId == dispatcherId
        }
    }

    private fun handleShiftEnter(e: KeyEvent) {
        val parent = findParent()
        if (parent is PromptTextField) {
            runUndoTransparentWriteAction {
                val document = parent.document
                val caretModel = parent.editor?.caretModel
                val offset = caretModel?.offset ?: return@runUndoTransparentWriteAction

                val lineEndOffset = document.getLineEndOffset(document.getLineNumber(offset))
                val remainingText = if (offset < lineEndOffset) {
                    val textAfterCursor = document.getText(TextRange(offset, lineEndOffset))
                    document.deleteString(offset, lineEndOffset)
                    textAfterCursor
                } else {
                    ""
                }

                document.insertString(offset, "\n" + remainingText)
                caretModel.moveToOffset(offset + 1)
            }
            e.consume()
        }
    }
}
