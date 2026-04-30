package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.util.text.matching.MatchedFragment

class LookupItemKeepOpenPrefixMatcher(
    prefix: String,
    private val highlightText: String,
    private val highlightTextProvider: (() -> String)?
) : PrefixMatcher(prefix) {

    override fun prefixMatches(name: String): Boolean = true

    override fun cloneWithPrefix(prefix: String): PrefixMatcher {
        return LookupItemKeepOpenPrefixMatcher(prefix, highlightText, highlightTextProvider)
    }

    override fun matchingDegree(string: String): Int = 0

    override fun getMatchingFragments(
        pattern: String,
        name: String
    ): MutableList<MatchedFragment>? {
        val effectivePattern = (highlightTextProvider?.invoke() ?: highlightText).trim()
        if (effectivePattern.isEmpty()) {
            return null
        }

        return LookupMatchers.createMatcher(effectivePattern).match(name)?.toMutableList()
    }
}
