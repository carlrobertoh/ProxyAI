package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.MinusculeMatcher
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupMatchers
import ee.carlrobert.codegpt.ui.textarea.lookup.action.FolderActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.ImageActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.WebActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem
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
    private val filesGroupItem = FilesGroupItem(project, tagManager, fileSearchProvider)
    private var cachedSearchText: String = ""
    private var cachedSearchResults: List<LookupActionItem> = emptyList()

    companion object {
        private val logger = thisLogger()
    }

    fun getDefaultGroups() = when (featureType) {
        FeatureType.INLINE_EDIT -> getInlineEditGroups()
        FeatureType.AGENT -> getAgentGroups()
        else -> getAllGroups()
    }

    private fun getInlineEditGroups() = listOfNotNull(
        filesGroupItem,
        if (GitFeatureAvailability.isAvailable) GitGroupItem(project) else null,
        HistoryGroupItem(),
        DiagnosticsGroupItem(tagManager)
    ).filter { it.enabled }

    private fun getAgentGroups() = listOfNotNull(
        filesGroupItem,
        if (GitFeatureAvailability.isAvailable) GitGroupItem(project) else null,
        MCPGroupItem(tagManager),
        DiagnosticsGroupItem(tagManager),
        ImageActionItem(project, tagManager)
    ).filter { it.enabled }

    private fun getAllGroups() = listOfNotNull(
        filesGroupItem,
        if (GitFeatureAvailability.isAvailable) GitGroupItem(project) else null,
        HistoryGroupItem(),
        PersonasGroupItem(tagManager),
        MCPGroupItem(tagManager),
        DiagnosticsGroupItem(tagManager),
        WebActionItem(tagManager),
        ImageActionItem(project, tagManager)
    ).filter { it.enabled }

    suspend fun performInstantSearch(searchText: String): List<LookupActionItem> {
        val groups = getDefaultGroups()
        val results = mutableListOf<LookupActionItem>()

        if (GitFeatureAvailability.isAvailable && groups.any { it is GitGroupItem }) {
            results.add(IncludeCurrentChangesActionItem())
        }

        val lightGroups = groups
            .filterNot { it is FilesGroupItem || it is GitGroupItem }
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

        return try {
            fileGroup.getLookupItems(searchText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error getting results from ${fileGroup::class.simpleName}", e)
            emptyList()
        }
    }

    suspend fun performDeferredHeavySearch(searchText: String): List<LookupActionItem> =
        coroutineScope {
            val deferredGroups = getDefaultGroups()
                .filterIsInstance<GitGroupItem>()

            deferredGroups.map { group ->
                async {
                    try {
                        group.getLookupItems(searchText)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(
                            "Error getting deferred results from ${group::class.simpleName}",
                            e
                        )
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
        val combined = (primaryResults + secondaryResults)
            .filter { candidate -> seenKeys.add(resultKey(candidate)) }

        return filterAndSortResults(combined, searchText)
    }

    fun getOptimisticSearchResults(searchText: String): List<LookupActionItem> {
        val normalizedSearchText = searchText.trim()
        val cachedText = cachedSearchText
        if (normalizedSearchText.isEmpty() ||
            cachedText.isEmpty() ||
            !normalizedSearchText.startsWith(cachedText, ignoreCase = true)
        ) {
            return emptyList()
        }

        return filterAndSortResults(cachedSearchResults, normalizedSearchText)
    }

    fun cacheSearchResults(
        searchText: String,
        results: List<LookupActionItem>
    ) {
        cachedSearchText = searchText.trim()
        cachedSearchResults = results
    }

    fun clearSearchResultsCache() {
        cachedSearchText = ""
        cachedSearchResults = emptyList()
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
        return LookupMatchers.createMatcher(searchText)
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

            is FileActionItem -> {
                maxOf(
                    matcher.matchingDegree(result.displayName),
                    matcher.matchingDegree(result.file.path)
                )
            }

            is FolderActionItem -> {
                maxOf(
                    matcher.matchingDegree(result.displayName),
                    matcher.matchingDegree(result.folder.path)
                )
            }

            else -> {
                matcher.matchingDegree(result.displayName)
            }
        }
    }

    private fun resultKey(result: LookupActionItem): String {
        return when (result) {
            is FileActionItem ->
                "file:${result.file.path}"

            is FolderActionItem ->
                "folder:${result.folder.path}"

            else -> "${result::class.qualifiedName}:${result.displayName}"
        }
    }

    fun getSearchTextAfterAt(text: String, caretOffset: Int): String? {
        return AtLookupToken.from(text, caretOffset)?.searchText
    }

    fun matchesAnyDefaultGroup(searchText: String): Boolean {
        return PromptTextFieldConstants.DEFAULT_GROUP_NAMES.any { groupName ->
            groupName.startsWith(searchText, ignoreCase = true)
        }
    }
}
