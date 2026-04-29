package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.util.text.matching.MatchingMode

object LookupMatchers {

    fun createMatcher(searchText: String): MinusculeMatcher {
        val normalizedSearchText = searchText.trim()
        return if (normalizedSearchText.isEmpty()) {
            NameUtil.buildMatcher("*").build()
        } else {
            NameUtil.buildMatcherWithFallback(
                normalizedSearchText,
                "*$normalizedSearchText*",
                MatchingMode.IGNORE_CASE
            )
        }
    }
}
