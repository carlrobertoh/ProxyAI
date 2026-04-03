package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
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
                    emphasizeMatch(presentation, searchText)
                }
            })
            .apply {
                putUserData(LookupItem.KEY, this@AbstractLookupItem)
            }
        return PrioritizedLookupElement.withPriority(lookupElement, 1.0)
    }

    private fun emphasizeMatch(
        presentation: LookupElementPresentation,
        searchText: String
    ) {
        val normalizedSearchText = searchText.trim()
        if (normalizedSearchText.isEmpty()) {
            return
        }

        val matcher = NameUtil.buildMatcher("*$normalizedSearchText").build()
        if (matcher.matchingFragments(displayName) != null) {
            presentation.isItemTextBold = true
        }
    }

    abstract fun getLookupString(): String
}
