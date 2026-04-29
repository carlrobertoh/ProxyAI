package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.completion.PrefixMatcher

class LookupItemPrefixMatcher(prefix: String) : PrefixMatcher(prefix) {

    override fun prefixMatches(name: String): Boolean {
        return LookupMatchers.createMatcher(prefix).matchingDegree(name) != Int.MIN_VALUE
    }

    override fun cloneWithPrefix(prefix: String): PrefixMatcher {
        return LookupItemPrefixMatcher(prefix)
    }

    override fun matchingDegree(string: String): Int {
        return LookupMatchers.createMatcher(prefix).matchingDegree(string)
    }
}
