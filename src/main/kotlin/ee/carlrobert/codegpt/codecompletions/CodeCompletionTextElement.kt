package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange

class CodeCompletionTextElement(
    override val text: String,
    private val insertOffset: Int,
    val textRange: TextRange,
    val offsetDelta: Int = 0,
    val originalText: String = text,
    val isDone: Boolean = false,
) : InlineCompletionElement {

    override fun toPresentable(): InlineCompletionElement.Presentable =
        Presentable(this, insertOffset, textRange)

    open class Presentable(
        element: InlineCompletionElement,
        private val insertOffset: Int,
        private val textRange: TextRange,
    ) : InlineCompletionGrayTextElement.Presentable(element) {

        override fun render(editor: Editor, offset: Int) {
            super.render(editor, insertOffset)
        }

        override fun startOffset(): Int {
            return textRange.startOffset
        }

        override fun endOffset(): Int {
            return textRange.endOffset
        }
    }
}
