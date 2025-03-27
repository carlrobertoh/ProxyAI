package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.impl.LookupImpl

object LookupUtil {

    fun addLookupItem(lookup: LookupImpl, lookupItem: LookupItem, priority: Double = 5.0) {
        if (!lookup.isLookupDisposed) {
            lookup.addItem(
                PrioritizedLookupElement.withPriority(lookupItem.createLookupElement(), priority),
                PrefixMatcher.ALWAYS_TRUE
            )
            lookup.refreshUi(true, true)
        }
    }
}