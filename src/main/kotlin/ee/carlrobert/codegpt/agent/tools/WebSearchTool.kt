package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class WebSearchTool(
    workingDirectory: String,
    sessionId: String,
    hookManager: HookManager,
    private val userAgent: String = "Mozilla/5.0 (compatible; ProxyAI/1.0; +https://tryproxy.io)",
) : BaseTool<WebSearchTool.Args, WebSearchTool.Result>(
    workingDirectory = workingDirectory,
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "WebSearch",
    description = """
IMPORTANT - When NOT to use this tool:
  - DO NOT use WebSearch for library, framework, or technical documentation queries
  - For questions about libraries, frameworks, SDKs, or APIs (e.g., "How to use React hooks?", "Python pandas documentation", "Spring Boot setup"), you MUST use ResolveLibraryId and GetLibraryDocs tools instead
  - WebSearch is for general web content, current events, and non-technical documentation queries only

- Allows Claude to search the web and use the results to inform responses
- Provides up-to-date information for current events and recent data
- Returns search result information formatted as search result blocks, including links as markdown hyperlinks
- Use this tool for accessing information beyond Claude's knowledge cutoff
- Searches are performed automatically within a single API call

CRITICAL REQUIREMENT - You MUST follow this:
  - After answering the user's question, you MUST include a "Sources:" section at the end of your response
  - In the Sources section, list all relevant URLs from the search results as markdown hyperlinks: [Title](URL)
  - This is MANDATORY - never skip including sources in your response
  - Example format:

    [Your answer here]

    Sources:
    - [Source Title 1](https://example.com/1)
    - [Source Title 2](https://example.com/2)

Usage notes:
  - Domain filtering is supported to include or block specific websites
  - Web search is only available in the US

IMPORTANT - Use the correct year in search queries:
  - Today's date is ${
        LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }. You MUST use this year when searching for recent information, documentation, or current events.
  - Example: If today is 2025-07-15 and the user asks for "latest React docs", search for "React documentation 2025", NOT "React documentation 2024"
""".trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
    sessionId = sessionId,
) {
    @Serializable
    data class Args(
        @property:LLMDescription(
            "The search query to use"
        )
        val query: String,
        @property:LLMDescription(
            "Only include search results from these domains"
        )
        @SerialName("allowed_domains")
        val allowedDomains: List<String>? = null,
        @property:LLMDescription(
            "Never include search results from these domains"
        )
        @SerialName("blocked_domains")
        val blockedDomains: List<String>? = null
    )

    @Serializable
    data class SearchResult(
        val title: String,
        val url: String,
        val content: String
    )

    @Serializable
    data class Result(
        val query: String,
        val results: List<SearchResult>,
        val sources: List<String>
    )

    override suspend fun doExecute(args: Args): Result = withContext(Dispatchers.IO) {
        try {
            val duckduckgoResults = searchWithDuckDuckGo(args.query)
            val filteredResults = filterResults(
                duckduckgoResults,
                args.allowedDomains,
                args.blockedDomains
            ).take(10)
            val result = Result(
                query = args.query,
                results = filteredResults,
                sources = filteredResults.map { "[${it.title}](${it.url})" }
            )
            return@withContext result
        } catch (_: Exception) {
            return@withContext Result(
                query = args.query,
                results = emptyList(),
                sources = emptyList()
            )
        }
    }

    override fun createDeniedResult(
        originalArgs: Args,
        deniedReason: String
    ): Result {
        return Result(
            query = originalArgs.query,
            results = emptyList(),
            sources = listOf(deniedReason)
        )
    }

    private suspend fun searchWithDuckDuckGo(query: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url = "https://duckduckgo.com/html/?q=$encodedQuery"

            val doc = Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(10000)
                .get()

            val results = mutableListOf<SearchResult>()

            doc.select("div.result").forEach { resultDiv ->
                val titleLink = resultDiv.selectFirst("a.result__a")
                val snippet = resultDiv.selectFirst("a.result__snippet")

                if (titleLink != null) {
                    val resolvedUrl = normalizeSearchResultUrl(titleLink.attr("href"))
                    results.add(
                        SearchResult(
                            title = titleLink.text(),
                            url = resolvedUrl,
                            content = snippet?.text() ?: ""
                        )
                    )
                }
            }

            results
        }

    private fun filterResults(
        results: List<SearchResult>,
        allowedDomains: List<String>?,
        blockedDomains: List<String>?
    ): List<SearchResult> {
        val effectiveAllowedDomains = normalizeDomains(allowedDomains)
        val effectiveBlockedDomains = normalizeDomains(blockedDomains)
        return results.filter { result ->
            val urlLower = result.url.lowercase()

            effectiveBlockedDomains?.any { domain ->
                urlLower.contains(domain.lowercase())
            }?.let { if (it) return@filter false }

            effectiveAllowedDomains?.let { allowed ->
                allowed.any { domain ->
                    urlLower.contains(domain.lowercase())
                }
            } ?: true
        }
    }

    companion object {
        internal fun normalizeSearchResultUrl(url: String): String {
            if (url.isBlank()) return url

            val absoluteUrl = if (url.startsWith("//")) {
                "https:$url"
            } else {
                url
            }

            return runCatching {
                val uri = URI(absoluteUrl)
                val host = uri.host?.lowercase()
                if (host != "duckduckgo.com" && host != "www.duckduckgo.com") {
                    return absoluteUrl
                }

                val query = uri.rawQuery ?: return absoluteUrl
                query.split("&")
                    .firstNotNullOfOrNull { segment ->
                        val idx = segment.indexOf('=')
                        if (idx <= 0) return@firstNotNullOfOrNull null
                        val key = segment.substring(0, idx)
                        if (key != "uddg") return@firstNotNullOfOrNull null
                        URLDecoder.decode(segment.substring(idx + 1), StandardCharsets.UTF_8)
                    }
                    ?: absoluteUrl
            }.getOrDefault(absoluteUrl)
        }

        internal fun normalizeDomains(domains: List<String>?): List<String>? {
            return domains
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
        }
    }

    override fun encodeResultToString(result: Result): String =
        buildString {
            if (result.results.isEmpty()) {
                appendLine("No search results found for query: ${result.query}")
                return@buildString
            }

            appendLine("[${result.results.size} results]")
            appendLine()

            result.results.forEachIndexed { index, searchResult ->
                appendLine("[${index + 1}. ${searchResult.title}](${searchResult.url})")
            }

            appendLine()
            appendLine("*Click on any result to view the full content*")
        }.trimEnd().truncateToolResult()
}
