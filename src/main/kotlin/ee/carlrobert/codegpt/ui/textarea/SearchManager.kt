package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.CodeAnalyzeActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.WebActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.ImageActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.DiagnosticsActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.group.*
import kotlinx.coroutines.CancellationException

data class SearchState(
    val isInSearchContext: Boolean = false,
    val isInGroupLookupContext: Boolean = false,
    val lastSearchText: String? = null
)

class SearchManager(
    private val project: Project,
    private val tagManager: TagManager
) {
    companion object {
        private val logger = thisLogger()
    }

    fun getDefaultGroups() = listOf(
        FilesGroupItem(project, tagManager),
        FoldersGroupItem(project, tagManager),
        GitGroupItem(project),
        HistoryGroupItem(),
        PersonasGroupItem(tagManager),
        DocsGroupItem(tagManager),
        CodeAnalyzeActionItem(tagManager),
        DiagnosticsActionItem(tagManager),
        MCPGroupItem(),
        WebActionItem(tagManager),
        ImageActionItem(project, tagManager)
    ).filter { it.enabled }

    suspend fun performGlobalSearch(searchText: String): List<LookupActionItem> {
        val allGroups = getDefaultGroups().filterNot { it is WebActionItem || it is ImageActionItem }
        val allResults = mutableListOf<LookupActionItem>()

        allGroups.forEach { group ->
            try {
                if (group is LookupGroupItem) {
                    val lookupActionItems =
                        group.getLookupItems("").filterIsInstance<LookupActionItem>()
                    allResults.addAll(lookupActionItems)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error getting results from ${group::class.simpleName}", e)
            }
        }

        val webAction = WebActionItem(tagManager)
        if (webAction.enabled()) {
            allResults.add(webAction)
        }

        return filterAndSortResults(allResults, searchText)
    }

    private fun filterAndSortResults(
        results: List<LookupActionItem>,
        searchText: String
    ): List<LookupActionItem> {
        val matcher: MinusculeMatcher = NameUtil.buildMatcher("*$searchText").build()

        return results.mapNotNull { result ->
            when (result) {
                is WebActionItem -> {
                    if (searchText.contains("web", ignoreCase = true)) {
                        result to 100
                    } else null
                }

                else -> {
                    val matchingDegree = matcher.matchingDegree(result.displayName)
                    if (matchingDegree != Int.MIN_VALUE) {
                        result to matchingDegree
                    } else null
                }
            }
        }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(PromptTextFieldConstants.MAX_SEARCH_RESULTS)
    }

    fun getSearchTextAfterAt(text: String, caretOffset: Int): String? {
        val atPos = text.lastIndexOf(PromptTextFieldConstants.AT_SYMBOL)
        if (atPos == -1 || atPos >= caretOffset) return null

        val searchText = text.substring(atPos + 1, caretOffset)
        return if (searchText.contains(PromptTextFieldConstants.SPACE) ||
            searchText.contains(PromptTextFieldConstants.NEWLINE)
        ) {
            null
        } else {
            searchText
        }
    }

    fun matchesAnyDefaultGroup(searchText: String): Boolean {
        return PromptTextFieldConstants.DEFAULT_GROUP_NAMES.any { groupName ->
            groupName.startsWith(searchText, ignoreCase = true)
        }
    }
}