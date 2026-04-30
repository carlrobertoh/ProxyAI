package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.impl.LookupImpl

object LookupUtil {
    fun addLookupItems(
        lookup: LookupImpl,
        lookupItems: List<Pair<LookupItem, Double>>,
        searchText: String = "",
        searchTextProvider: (() -> String)? = null,
        matcherPrefix: String = searchText
    ) {
        if (!lookup.isLookupDisposed) {
            val prefixMatcher = LookupItemKeepOpenPrefixMatcher(
                matcherPrefix,
                searchText,
                searchTextProvider
            )
            lookupItems.forEach { (lookupItem, priority) ->
                lookup.addItem(
                    PrioritizedLookupElement.withPriority(
                        lookupItem.createLookupElement(searchText, searchTextProvider),
                        priority
                    ),
                    prefixMatcher
                )
            }
            lookup.refreshUi(true, true)
        }
    }
}
