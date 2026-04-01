package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.codeInsight.lookup.LookupElementRenderer

abstract class AbstractLookupItem : LookupItem {
    override fun createLookupElement(searchText: String): LookupElement {
        val lookupElement = LookupElementBuilder.create(getLookupString())
            .withPresentableText(displayName)
            .withIcon(icon)
            .withRenderer(object : LookupElementRenderer<LookupElement>() {
                override fun renderElement(
                    element: LookupElement,
                    presentation: LookupElementPresentation
                ) {
                    setPresentation(element, presentation)
                    highlightMatches(presentation, searchText)
                }
            })
            .apply {
                putUserData(LookupItem.KEY, this@AbstractLookupItem)
            }
        return PrioritizedLookupElement.withPriority(lookupElement, 1.0)
    }

    private fun highlightMatches(
        presentation: LookupElementPresentation,
        searchText: String
    ) {
        val normalizedSearchText = searchText.trim()
        if (normalizedSearchText.isEmpty()) {
            return
        }

        val matcher = NameUtil.buildMatcher("*$normalizedSearchText").build()
        matcher.matchingFragments(displayName)
            ?.asReversed()
            ?.forEach { range: TextRange ->
                presentation.decorateItemTextRange(
                    range,
                    LookupElementPresentation.LookupItemDecoration.HIGHLIGHT_MATCHED
                )
            }
    }

    abstract fun getLookupString(): String
}
