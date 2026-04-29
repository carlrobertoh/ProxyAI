package ee.carlrobert.codegpt.ui.textarea

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class AtLookupTokenTest {

    @Test
    fun `test resolves lookup token before caret`() {
        val text = "before @first middle @second after"
        val caretOffset = text.indexOf(" after")

        val token = AtLookupToken.from(text, caretOffset)

        assertThat(token).isEqualTo(
            AtLookupToken(
                startOffset = text.indexOf("@second"),
                endOffset = caretOffset,
                searchText = "second"
            )
        )
    }

    @Test
    fun `test ignores at symbols after caret`() {
        val text = "before @first middle @second"
        val caretOffset = text.indexOf(" middle")

        val token = AtLookupToken.from(text, caretOffset)

        assertThat(token).isEqualTo(
            AtLookupToken(
                startOffset = text.indexOf("@first"),
                endOffset = caretOffset,
                searchText = "first"
            )
        )
    }

    @Test
    fun `test rejects token with whitespace`() {
        val text = "before @not a token"

        val token = AtLookupToken.from(text, text.length)

        assertThat(token).isNull()
    }
}
