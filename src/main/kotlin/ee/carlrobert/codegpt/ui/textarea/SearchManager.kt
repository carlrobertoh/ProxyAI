package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.ImageActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.WebActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.IncludeOpenFilesActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.git.IncludeCurrentChangesActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.group.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class SearchState(
    val isInSearchContext: Boolean = false,
    val isInGroupLookupContext: Boolean = false,
    val lastSearchText: String? = null
)

class SearchManager(
    private val project: Project,
    private val tagManager: TagManager,
    private val featureType: FeatureType? = null,
) {
    private val fileSearchProvider = NativeFileSearchProvider(project)

    companion object {
        private val logger = thisLogger()
    }

    fun getDefaultGroups() = when (featureType) {
        FeatureType.INLINE_EDIT -> getInlineEditGroups()
        FeatureType.AGENT -> getAgentGroups()
        else -> getAllGroups()
    }

    private fun getInlineEditGroups() = listOfNotNull(
        FilesGroupItem(project, tagManager, fileSearchProvider),
        FoldersGroupItem(project, tagManager),
        if (GitFeatureAvailability.isAvailable) GitGroupItem(project) else null,
        HistoryGroupItem(),
        DiagnosticsGroupItem(tagManager)
    ).filter { it.enabled }

    private fun getAgentGroups() = listOfNotNull(
        FilesGroupItem(project, tagManager, fileSearchProvider),
        FoldersGroupItem(project, tagManager),
        if (GitFeatureAvailability.isAvailable) GitGroupItem(project) else null,
        MCPGroupItem(tagManager, FeatureType.AGENT),
        DiagnosticsGroupItem(tagManager),
        ImageActionItem(project, tagManager)
    ).filter { it.enabled }

    private fun getAllGroups() = listOfNotNull(
        FilesGroupItem(project, tagManager, fileSearchProvider),
        FoldersGroupItem(project, tagManager),
        if (GitFeatureAvailability.isAvailable) GitGroupItem(project) else null,
        HistoryGroupItem(),
        PersonasGroupItem(tagManager),
        MCPGroupItem(tagManager, featureType ?: FeatureType.CHAT),
        DiagnosticsGroupItem(tagManager),
        WebActionItem(tagManager),
        ImageActionItem(project, tagManager)
    ).filter { it.enabled }

    suspend fun performInstantSearch(searchText: String): List<LookupActionItem> {
        val groups = getDefaultGroups()
        val results = mutableListOf<LookupActionItem>()

        // Standalone action items that are normally buried inside heavy groups
        if (groups.any { it is FilesGroupItem }) {
            results.add(IncludeOpenFilesActionItem())
        }
        if (GitFeatureAvailability.isAvailable && groups.any { it is GitGroupItem }) {
            results.add(IncludeCurrentChangesActionItem())
        }

        // Lightweight groups (in-memory data only)
        val lightGroups = groups
            .filterNot { it is FilesGroupItem || it is FoldersGroupItem || it is GitGroupItem }
            .filterNot { it is WebActionItem || it is ImageActionItem }

        lightGroups.forEach { group ->
            try {
                if (group is LookupGroupItem) {
                    results.addAll(
                        group.getLookupItems(searchText).filterIsInstance<LookupActionItem>()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error getting instant results from ${group::class.simpleName}", e)
            }
        }

        if (featureType != FeatureType.INLINE_EDIT && featureType != FeatureType.AGENT) {
            val webAction = WebActionItem(tagManager)
            if (webAction.enabled()) {
                results.add(webAction)
            }
        }

        return filterAndSortResults(results, searchText)
    }

    suspend fun performFileSearch(searchText: String): List<LookupActionItem> {
        val fileGroup = getDefaultGroups().filterIsInstance<FilesGroupItem>().firstOrNull()
            ?: return emptyList()
        val matcher = createMatcher(searchText)

        return try {
            fileGroup.getLookupItems(searchText)
                .filterIsInstance<LookupActionItem>()
                .filter { result ->
                    result !is IncludeOpenFilesActionItem || matchesSearchText(result, searchText, matcher)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error getting results from ${fileGroup::class.simpleName}", e)
            emptyList()
        }
    }

    suspend fun performDeferredHeavySearch(searchText: String): List<LookupActionItem> = coroutineScope {
        val deferredGroups = getDefaultGroups()
            .filter { it is FoldersGroupItem || it is GitGroupItem }

        deferredGroups.map { group ->
            async {
                try {
                    if (group is LookupGroupItem) {
                        group.getLookupItems(searchText).filterIsInstance<LookupActionItem>()
                    } else {
                        emptyList()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error getting deferred results from ${group::class.simpleName}", e)
                    emptyList()
                }
            }
        }.flatMap { it.await() }
    }

    fun mergeResults(
        primaryResults: List<LookupActionItem>,
        secondaryResults: List<LookupActionItem>,
        searchText: String
    ): List<LookupActionItem> {
        val seenKeys = mutableSetOf<String>()
        val orderedPrimary = primaryResults
            .filter { candidate -> seenKeys.add(resultKey(candidate)) }
        val orderedSecondary = filterAndSortResults(secondaryResults, searchText)
            .filter { candidate -> seenKeys.add(resultKey(candidate)) }

        return (orderedPrimary + orderedSecondary)
            .take(PromptTextFieldConstants.MAX_SEARCH_RESULTS)
    }

    suspend fun performGlobalSearch(searchText: String): List<LookupActionItem> {
        val instant = performInstantSearch(searchText)
        val fileResults = performFileSearch(searchText)
        val earlyResults = mergeResults(fileResults, instant, searchText)
        val deferredResults = performDeferredHeavySearch(searchText)
        return mergeResults(earlyResults, deferredResults, searchText)
    }

    private fun filterAndSortResults(
        results: List<LookupActionItem>,
        searchText: String
    ): List<LookupActionItem> {
        val matcher = createMatcher(searchText)

        return results.mapNotNull { result ->
            val matchingDegree = getMatchingDegree(result, searchText, matcher)
            if (matchingDegree != Int.MIN_VALUE) {
                result to matchingDegree
            } else null
        }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(PromptTextFieldConstants.MAX_SEARCH_RESULTS)
    }

    private fun createMatcher(searchText: String): MinusculeMatcher {
        return NameUtil.buildMatcher("*$searchText").build()
    }

    private fun matchesSearchText(
        result: LookupActionItem,
        searchText: String,
        matcher: MinusculeMatcher
    ): Boolean {
        return getMatchingDegree(result, searchText, matcher) != Int.MIN_VALUE
    }

    private fun getMatchingDegree(
        result: LookupActionItem,
        searchText: String,
        matcher: MinusculeMatcher
    ): Int {
        return when (result) {
            is WebActionItem -> {
                if (searchText.contains("web", ignoreCase = true)) {
                    100
                } else {
                    Int.MIN_VALUE
                }
            }

            else -> {
                matcher.matchingDegree(result.displayName)
            }
        }
    }

    private fun resultKey(result: LookupActionItem): String {
        return when (result) {
            is ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem ->
                "file:${result.file.path}"

            else -> "${result::class.qualifiedName}:${result.displayName}"
        }
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

}
