package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.openapi.util.TextRange

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
                    emphasizeMatch(
                        presentation,
                        searchTextProvider?.invoke() ?: searchText
                    )
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

    private fun emphasizeMatch(
        presentation: LookupElementPresentation,
        searchText: String
    ) {
        val normalizedSearchText = searchText.trim()
        if (normalizedSearchText.isEmpty()) {
            return
        }

        val matcher = LookupMatchers.createMatcher(normalizedSearchText)
        matcher.match(displayName)?.forEach { fragment ->
            presentation.decorateItemTextRange(
                TextRange(fragment.startOffset, fragment.endOffset),
                LookupElementPresentation.LookupItemDecoration.HIGHLIGHT_MATCHED
            )
        }
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
