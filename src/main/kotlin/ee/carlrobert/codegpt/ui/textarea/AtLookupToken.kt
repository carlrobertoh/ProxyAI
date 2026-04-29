package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.editor.Editor

data class AtLookupToken(
    val startOffset: Int,
    val endOffset: Int,
    val searchText: String
) {
    val prefix: String
        get() = PromptTextFieldConstants.AT_SYMBOL + searchText

    companion object {
        fun from(editor: Editor): AtLookupToken? {
            val text = editor.document.text
            return from(text, editor.caretModel.offset)
        }

        fun from(text: String, caretOffset: Int): AtLookupToken? {
            val boundedCaretOffset = caretOffset.coerceIn(0, text.length)
            if (boundedCaretOffset == 0) {
                return null
            }

            val startOffset = text.lastIndexOf(
                PromptTextFieldConstants.AT_SYMBOL,
                boundedCaretOffset - 1
            )
            if (startOffset == -1 || startOffset >= boundedCaretOffset) {
                return null
            }

            val searchText = text.substring(startOffset + 1, boundedCaretOffset)
            return if (searchText.contains(PromptTextFieldConstants.SPACE) ||
                searchText.contains(PromptTextFieldConstants.NEWLINE)
            ) {
                null
            } else {
                AtLookupToken(startOffset, boundedCaretOffset, searchText)
            }
        }
    }
}
