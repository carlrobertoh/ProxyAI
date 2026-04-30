package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer

abstract class AbstractLookupItem : LookupItem {
    override fun createLookupElement(
        searchText: String,
        searchTextProvider: (() -> String)?
    ): LookupElement {
        var builder = LookupElementBuilder.create(getLookupString())
            .withPresentableText(displayName)
            .withIcon(icon)
            .withInsertHandler { context, _ -> removeInsertedLookupText(context) }
            .withRenderer(object : LookupElementRenderer<LookupElement>() {
                override fun renderElement(
                    element: LookupElement,
                    presentation: LookupElementPresentation
                ) {
                    setPresentation(element, presentation)
                }
            })
        getAdditionalLookupStrings().forEach { lookupString ->
            builder = builder.withLookupString(lookupString)
        }
        val lookupElement = builder.apply {
            putUserData(LookupItem.KEY, this@AbstractLookupItem)
        }
        return PrioritizedLookupElement.withPriority(lookupElement, 1.0)
    }

    abstract fun getLookupString(): String

    protected open fun getAdditionalLookupStrings(): Collection<String> = emptyList()

    private fun removeInsertedLookupText(context: InsertionContext) {
        val startOffset = context.startOffset
        val tailOffset = context.tailOffset
        val document = context.document
        if (startOffset in 0..tailOffset && tailOffset <= document.textLength) {
            document.deleteString(startOffset, tailOffset)
            context.editor.caretModel.moveToOffset(startOffset)
            context.tailOffset = startOffset
        }
        context.setAddCompletionChar(false)
    }
}
