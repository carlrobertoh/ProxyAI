package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.net.URI

class WebFetchTool(
    workingDirectory: String,
    private val userAgent: String = "Mozilla/5.0 (compatible; ProxyAI/1.0; +https://tryproxy.io)",
    sessionId: String,
    hookManager: HookManager,
) : BaseTool<WebFetchTool.Args, WebFetchTool.Result>(
    workingDirectory = workingDirectory,
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "WebFetch",
    description = """
Use this tool when you already have a specific URL and need the page content as Markdown.

- Fetches a web page URL and converts HTML to Markdown using flexmark.
- Returns normalized metadata (title, final URL, status code, content type) and Markdown content.
- Use WebSearch to discover sources; use WebFetch to extract content from a known source URL.
""".trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
    sessionId = sessionId,
) {

    @Serializable
    data class Args(
        @property:LLMDescription("The URL to fetch. Must start with http:// or https://.")
        val url: String,
        @property:LLMDescription("Optional CSS selector to limit extraction to specific elements (for example: 'main', 'article', '.docs-content').")
        val selector: String? = null,
        @property:LLMDescription("Request timeout in milliseconds. Defaults to 10000. Accepted range is 1000 to 60000.")
        @SerialName("timeout_ms")
        val timeoutMs: Int = 10_000,
        @property:LLMDescription(
            "Optional line number to start reading from in the extracted markdown (1-indexed). Useful for paginating long pages."
        )
        val offset: Int? = null,
        @property:LLMDescription(
            "Optional number of markdown lines to return from the offset. Use with offset for paging."
        )
        val limit: Int? = null
    )

    @Serializable
    data class Result(
        val url: String,
        @SerialName("final_url")
        val finalUrl: String? = null,
        val title: String? = null,
        val markdown: String = "",
        @SerialName("content_type")
        val contentType: String? = null,
        @SerialName("status_code")
        val statusCode: Int? = null,
        @SerialName("used_selector")
        val usedSelector: String? = null,
        val truncated: Boolean = false,
        @SerialName("start_line")
        val startLine: Int? = null,
        @SerialName("end_line")
        val endLine: Int? = null,
        val error: String? = null
    )

    private val converter = FlexmarkHtmlConverter.builder().build()

    override suspend fun doExecute(args: Args): Result = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(args.url)
            ?: return@withContext Result(
                url = args.url,
                error = "Invalid URL. Only http:// and https:// are supported."
            )

        if (args.timeoutMs !in 1_000..60_000) {
            return@withContext Result(
                url = normalizedUrl,
                error = "Invalid timeout_ms value ${args.timeoutMs}. Expected range: 1000..60000."
            )
        }
        if (args.offset != null && args.offset < 1) {
            return@withContext Result(
                url = normalizedUrl,
                error = "Invalid offset value ${args.offset}. Expected offset >= 1."
            )
        }
        if (args.limit != null && args.limit < 1) {
            return@withContext Result(
                url = normalizedUrl,
                error = "Invalid limit value ${args.limit}. Expected limit >= 1."
            )
        }

        return@withContext try {
            val response = Jsoup.connect(normalizedUrl)
                .userAgent(userAgent)
                .ignoreContentType(true)
                .followRedirects(true)
                .timeout(args.timeoutMs)
                .maxBodySize(10 * 1024 * 1024)
                .execute()

            val contentType = response.contentType()
            if (!isHtmlContentType(contentType)) {
                return@withContext Result(
                    url = normalizedUrl,
                    finalUrl = response.url().toExternalForm(),
                    contentType = contentType,
                    statusCode = response.statusCode(),
                    error = "Unsupported content type: ${contentType ?: "unknown"}. Expected HTML."
                )
            }

            val body = response.body()
            if (body.isBlank()) {
                return@withContext Result(
                    url = normalizedUrl,
                    finalUrl = response.url().toExternalForm(),
                    contentType = contentType,
                    statusCode = response.statusCode(),
                    error = "Empty response body."
                )
            }

            val document = Jsoup.parse(body, response.url().toExternalForm())
            document.select("script, style, noscript, template").remove()

            val htmlToConvert = selectHtmlContent(document, args.selector)
                ?: return@withContext Result(
                    url = normalizedUrl,
                    finalUrl = response.url().toExternalForm(),
                    contentType = contentType,
                    statusCode = response.statusCode(),
                    error = "No content matched selector '${args.selector}'."
                )

            val markdown = converter.convert(htmlToConvert).trim()
            if (markdown.isEmpty()) {
                return@withContext Result(
                    url = normalizedUrl,
                    finalUrl = response.url().toExternalForm(),
                    title = document.title().takeIf { it.isNotBlank() },
                    contentType = contentType,
                    statusCode = response.statusCode(),
                    usedSelector = args.selector,
                    error = "No markdown content could be extracted from the fetched page."
                )
            }
            val markdownSlice = sliceMarkdown(
                markdown = markdown,
                offset = args.offset,
                limit = args.limit
            )

            Result(
                url = normalizedUrl,
                finalUrl = response.url().toExternalForm(),
                title = document.title().takeIf { it.isNotBlank() },
                markdown = markdownSlice.content,
                contentType = contentType,
                statusCode = response.statusCode(),
                usedSelector = args.selector,
                truncated = markdownSlice.truncated,
                startLine = markdownSlice.startLine,
                endLine = markdownSlice.endLine
            )
        } catch (e: Exception) {
            Result(
                url = normalizedUrl,
                error = "Failed to fetch URL: ${e.message ?: "Unknown error"}"
            )
        }
    }

    override fun createDeniedResult(originalArgs: Args, deniedReason: String): Result {
        return Result(url = originalArgs.url, error = deniedReason)
    }

    override fun encodeResultToString(result: Result): String {
        if (result.error != null) {
            return "Failed to fetch web page '${result.url}': ${result.error}".truncateToolResult()
        }

        val content = buildString {
            if (result.truncated) {
                append("Note: Content truncated")
                if (result.startLine != null || result.endLine != null) {
                    val start = result.startLine ?: "?"
                    val end = result.endLine ?: "?"
                    append(" (lines $start-$end)")
                }
                appendLine(". Use offset/limit to fetch another chunk.")
                appendLine()
            }
            append(result.markdown)
        }.trimEnd()

        return content.truncateToolResult()
    }

    private data class MarkdownSlice(
        val content: String,
        val truncated: Boolean,
        val startLine: Int?,
        val endLine: Int?,
    )

    private fun sliceMarkdown(
        markdown: String,
        offset: Int?,
        limit: Int?
    ): MarkdownSlice {
        val lines = markdown.lines()
        val totalLines = lines.size
        val hasLineSlice = offset != null || limit != null

        val startIndex = ((offset ?: 1) - 1).coerceIn(0, totalLines)
        val endExclusive = when (limit) {
            null -> totalLines
            else -> (startIndex + limit).coerceAtMost(totalLines)
        }

        val content = lines.subList(startIndex, endExclusive).joinToString("\n")
        val truncated = hasLineSlice && (startIndex > 0 || endExclusive < totalLines)

        return MarkdownSlice(
            content = content,
            truncated = truncated,
            startLine = if (hasLineSlice) startIndex + 1 else null,
            endLine = if (hasLineSlice) endExclusive else null
        )
    }

    private fun normalizeUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        return uri.toString()
    }

    private fun isHtmlContentType(contentType: String?): Boolean {
        if (contentType == null) return true
        val lower = contentType.lowercase()
        return lower.contains("text/html") || lower.contains("application/xhtml+xml")
    }

    private fun selectHtmlContent(document: org.jsoup.nodes.Document, selector: String?): String? {
        if (!selector.isNullOrBlank()) {
            val selected = document.select(selector)
            if (selected.isEmpty()) return null
            return selected.joinToString("\n") { it.outerHtml() }
        }

        val preferredRoot = document.selectFirst("main, article, [role=main]") ?: document.body()
        return preferredRoot?.outerHtml()
    }
}
