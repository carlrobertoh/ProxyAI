package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Tool for fetching library documentation from Context7.
 *
 * This tool retrieves documentation for a specific library using its
 * Context7-compatible ID. It supports both code documentation and
 * informational guides.
 */
class GetLibraryDocsTool(
    workingDirectory: String,
    hookManager: HookManager
) : BaseTool<GetLibraryDocsTool.Args, GetLibraryDocsTool.Result>(
    workingDirectory = workingDirectory,
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "GetLibraryDocs",
    description = """
        Use when the user asks how to use a library, best practices, APIs, configuration, or conventions. Resolve the library first with ResolveLibraryId unless the user provided a Context7-compatible ID. Use mode='code' for API/code, or mode='info' for guides.
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager
) {

    @Serializable
    data class Args(
        @property:LLMDescription(
            "Exact Context7-compatible library ID (e.g., '/mongodb/docs', '/vercel/next.js', '/supabase/supabase', '/vercel/next.js/v14.3.0-canary.87') retrieved from 'resolve-library-id' or directly from user query in the format '/org/project' or '/org/project/version'."
        )
        val context7CompatibleLibraryID: String,
        @property:LLMDescription(
            "Documentation mode: 'code' for API references and code examples (default), 'info' for conceptual guides, narrative information, and architectural questions."
        )
        val mode: String = "code",
        @property:LLMDescription(
            "Topic to focus documentation on (e.g., 'hooks', 'routing')."
        )
        val topic: String? = null,
        @property:LLMDescription(
            "Page number for pagination (start: 1, default: 1). If the context is not sufficient, try page=2, page=3, page=4, etc. with the same topic."
        )
        val page: Int = 1
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val libraryId: String,
            val documentation: String,
            val docMode: String,
            val page: Int
        ) : Result()

        @Serializable
        data class Error(
            val error: String
        ) : Result()
    }

    override suspend fun doExecute(args: Args): Result = withContext(Dispatchers.IO) {
        try {
            if (!args.context7CompatibleLibraryID.startsWith("/")) {
                return@withContext Result.Error(
                    "Invalid library ID format: '${args.context7CompatibleLibraryID}'. Library ID must start with '/' (e.g., '/vercel/next.js'). " +
                            "Use 'ResolveLibraryId' tool first to get the correct library ID format."
                )
            }

            if (args.mode.lowercase() !in listOf("code", "info")) {
                return@withContext Result.Error(
                    "Invalid documentation mode: '${args.mode}'. Must be either 'code' or 'info'."
                )
            }

            if (args.page !in 1..10) {
                return@withContext Result.Error(
                    "Invalid page number: ${args.page}. Page number must be between 1 and 10."
                )
            }

            val libraryComponents = parseLibraryId(args.context7CompatibleLibraryID)

            val baseUrl = "https://context7.com/api/v2/docs/${args.mode.lowercase()}"
            val urlBuilder =
                StringBuilder("$baseUrl/${libraryComponents.username}/${libraryComponents.library}")

            if (libraryComponents.tag != null) {
                urlBuilder.append("/${libraryComponents.tag}")
            }

            urlBuilder.append("?type=txt")
            if (args.topic != null) {
                urlBuilder.append(
                    "&topic=${
                        URLEncoder.encode(
                            args.topic,
                            StandardCharsets.UTF_8.toString()
                        )
                    }"
                )
            }
            if (args.page > 1) {
                urlBuilder.append("&page=${args.page}")
            }

            val url = urlBuilder.toString()

            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; ProxyAI/1.0; +https://tryproxy.io)")
                .timeout(10000)
                .ignoreContentType(true)
                .get()

            val documentation = doc.body().text().take(10_000)

            if (documentation.isEmpty()) {
                return@withContext Result.Error(
                    "No documentation available for library '${args.context7CompatibleLibraryID}' in ${args.mode} mode."
                )
            }

            if (documentation.contains("No content available") ||
                documentation.contains("No context data available")
            ) {
                val suggestion = if (args.mode.lowercase() == "code") {
                    " Try mode='info' for guides and tutorials."
                } else {
                    " Try mode='code' for API references and code examples."
                }
                return@withContext Result.Error(
                    "No ${args.mode} documentation available for this library.$suggestion"
                )
            }

            Result.Success(
                libraryId = args.context7CompatibleLibraryID,
                documentation = documentation,
                docMode = args.mode,
                page = args.page
            )
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("404", ignoreCase = true) == true ->
                    "The library '${args.context7CompatibleLibraryID}' was not found. Please check the library ID or use 'ResolveLibraryId' to find the correct ID."

                e.message?.contains("429", ignoreCase = true) == true ->
                    "Rate limited due to too many requests. Please try again later."

                e.message?.contains("401", ignoreCase = true) == true ->
                    "Unauthorized access to Context7 service."

                else ->
                    "Failed to fetch documentation: ${e.message}"
            }
            Result.Error(errorMessage)
        }
    }

    override fun createDeniedResult(
        originalArgs: Args,
        deniedReason: String
    ): Result {
        return Result.Error(error = deniedReason)
    }

    override fun encodeResultToString(result: Result): String = when (result) {
        is Result.Success -> result.documentation.truncateToolResult()
        is Result.Error -> ("Failed to retrieve library documentation: ${result.error}").truncateToolResult()
    }

    companion object {
        fun parseLibraryId(libraryId: String): LibraryComponents {
            val cleaned = libraryId.removePrefix("/")
            val parts = cleaned.split("/")

            if (parts.size < 2) {
                throw IllegalArgumentException(
                    "Invalid library ID format: $libraryId. Expected format: /username/library or /username/library/tag"
                )
            }

            return LibraryComponents(
                username = parts[0],
                library = parts[1],
                tag = if (parts.size > 2) parts[2] else null
            )
        }

        data class LibraryComponents(
            val username: String,
            val library: String,
            val tag: String?
        )
    }
}
