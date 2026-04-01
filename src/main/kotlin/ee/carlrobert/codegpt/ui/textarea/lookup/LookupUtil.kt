package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.impl.LookupImpl

object LookupUtil {

    fun addLookupItem(
        lookup: LookupImpl,
        lookupItem: LookupItem,
        priority: Double = 5.0,
        searchText: String = ""
    ) {
        addLookupItems(lookup, listOf(lookupItem to priority), searchText)
    }

    fun addLookupItems(
        lookup: LookupImpl,
        lookupItems: List<Pair<LookupItem, Double>>,
        searchText: String = ""
    ) {
        if (!lookup.isLookupDisposed) {
            lookupItems.forEach { (lookupItem, priority) ->
                lookup.addItem(
                    PrioritizedLookupElement.withPriority(
                        lookupItem.createLookupElement(searchText),
                        priority
                    ),
                    PrefixMatcher.ALWAYS_TRUE
                )
            }
            lookup.refreshUi(true, true)
        }
    }
}
