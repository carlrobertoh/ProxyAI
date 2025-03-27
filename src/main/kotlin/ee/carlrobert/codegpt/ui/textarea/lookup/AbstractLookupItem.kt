package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer

abstract class AbstractLookupItem : LookupItem {
    override fun createLookupElement(): LookupElement {
        val lookupElement = LookupElementBuilder.create(getLookupString())
            .withPresentableText(displayName)
            .withIcon(icon)
            .withRenderer(object : LookupElementRenderer<LookupElement>() {
                override fun renderElement(
                    element: LookupElement,
                    presentation: LookupElementPresentation
                ) {
                    setPresentation(element, presentation)
                }
            })
            .apply {
                putUserData(LookupItem.KEY, this@AbstractLookupItem)
            }
        return PrioritizedLookupElement.withPriority(lookupElement, 1.0)
    }

    abstract fun getLookupString(): String
}