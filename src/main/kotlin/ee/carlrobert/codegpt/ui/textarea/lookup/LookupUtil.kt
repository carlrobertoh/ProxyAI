package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.impl.LookupImpl

object LookupUtil {
    private fun keepLookupOpenMatcher(prefix: String): PrefixMatcher =
        object : PrefixMatcher(prefix) {
            override fun prefixMatches(name: String): Boolean = true

            override fun cloneWithPrefix(prefix: String): PrefixMatcher {
                return keepLookupOpenMatcher(prefix)
            }

            override fun matchingDegree(string: String): Int = 0
        }

    fun addLookupItems(
        lookup: LookupImpl,
        lookupItems: List<Pair<LookupItem, Double>>,
        searchText: String = "",
        searchTextProvider: (() -> String)? = null,
        matcherPrefix: String = searchText
    ) {
        if (!lookup.isLookupDisposed) {
            val prefixMatcher = keepLookupOpenMatcher(matcherPrefix)
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
