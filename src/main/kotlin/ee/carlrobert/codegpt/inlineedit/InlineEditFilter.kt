package ee.carlrobert.codegpt.inlineedit

import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchReplace

internal data class FilterResult(
    val pairs: List<Pair<String, String>>,
    val filteredCount: Int,
    val stats: Map<String, Int>
)

internal object InlineEditFilter {

    fun filterSegments(
        currentPath: String?,
        fileName: String?,
        fileExt: String?,
        segments: List<SearchReplace>
    ): FilterResult {
        fun fileMatches(path: String?): Boolean {
            if (path.isNullOrBlank()) return true
            if (currentPath == null) return false
            val name = fileName ?: ""
            return path == currentPath || path.endsWith("/" + name) || path == name
        }

        val accepted = mutableListOf<Pair<String, String>>()
        var filtered = 0
        val reasons = mutableMapOf<String, Int>()
        fun bump(reason: String) {
            reasons[reason] = (reasons[reason] ?: 0) + 1
        }

        for (seg in segments) {
            val search = seg.search
            val replace = seg.replace

            if (!fileMatches(seg.filePath)) {
                filtered++; bump("wrong-file"); continue
            }

            val normalizedSearch = search.trim()
            if (normalizedSearch.isBlank()) {
                filtered++; bump("empty-search"); continue
            }

            accepted.add(normalizedSearch to replace)
        }

        return FilterResult(accepted, filtered, reasons)
    }
}
