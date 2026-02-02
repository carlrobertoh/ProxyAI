package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Tool for resolving library names to Context7-compatible library IDs.
 */
class ResolveLibraryIdTool(
    workingDirectory: String,
    hookManager: HookManager,
) : BaseTool<ResolveLibraryIdTool.Args, ResolveLibraryIdTool.Result>(
    workingDirectory = workingDirectory,
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "ResolveLibraryId",
    description = """
        Use to resolve a library/package name into a Context7-compatible ID before fetching docs. Prefer this when the user asks about libraries, best practices, APIs, or configuration, unless they already provided a valid '/org/project[/version]' ID.
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager
) {

    @Serializable
    data class Args(
        @property:LLMDescription("Library name to search for and retrieve a Context7-compatible library ID.")
        @SerialName("library_name")
        val libraryName: String
    )

    @Serializable
    data class LibraryInfo(
        val id: String,
        val name: String,
        val description: String,
        val codeSnippets: Int,
        val sourceReputation: String,
        val benchmarkScore: Double,
        val versions: List<String>?
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val libraryName: String,
            val libraries: List<LibraryInfo>
        ) : Result()

        @Serializable
        data class Error(
            val error: String
        ) : Result()
    }

    override val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun doExecute(args: Args): Result = withContext(Dispatchers.IO) {
        try {
            val encodedQuery =
                URLEncoder.encode(args.libraryName, StandardCharsets.UTF_8.toString())
            val url = "https://context7.com/api/v2/search?query=$encodedQuery"

            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; ProxyAI/1.0; +https://tryproxy.io)")
                .timeout(10000)
                .ignoreContentType(true)
                .get()

            val jsonResponse = doc.body().text()
            if (jsonResponse.isEmpty()) {
                return@withContext Result.Error("Empty response from Context7 API")
            }

            try {
                val searchResponse = json.decodeFromString<Context7SearchResponse>(jsonResponse)
                val libraries = searchResponse.results.map { result ->
                    LibraryInfo(
                        id = result.id,
                        name = result.title ?: result.id.split("/").lastOrNull() ?: "Unknown",
                        description = result.description ?: "No description available",
                        codeSnippets = result.totalSnippets ?: 0,
                        sourceReputation = when {
                            result.trustScore == null -> "Unknown"
                            result.trustScore > 8.0 -> "High"
                            result.trustScore > 5.0 -> "Medium"
                            else -> "Low"
                        },
                        benchmarkScore = result.benchmarkScore ?: 0.0,
                        versions = result.versions
                    )
                }

                if (libraries.isEmpty()) {
                    return@withContext Result.Success(
                        libraryName = args.libraryName,
                        libraries = emptyList()
                    )
                }

                return@withContext Result.Success(
                    libraryName = args.libraryName,
                    libraries = libraries
                )
            } catch (e: Exception) {
                return@withContext Result.Error("Failed to parse Context7 response: ${e.message}")
            }
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("404", ignoreCase = true) == true ->
                    "The Context7 service is currently unavailable. Please try again later."

                e.message?.contains("429", ignoreCase = true) == true ->
                    "Rate limited due to too many requests. Please try again later."

                e.message?.contains("401", ignoreCase = true) == true ->
                    "Unauthorized access to Context7 service."

                else ->
                    "Failed to search for libraries: ${e.message}"
            }
            return@withContext Result.Error(errorMessage)
        }
    }

    override fun createDeniedResult(
        originalArgs: Args,
        deniedReason: String
    ): Result {
        return Result.Error(deniedReason)
    }

    override fun encodeResultToString(result: Result): String = when (result) {
        is Result.Success -> {
            val s = if (result.libraries.isEmpty()) {
                "No libraries found for '${result.libraryName}'. Please try with different search terms or check the library name spelling."
            } else {
                buildString {
                    appendLine("Available Libraries:")
                    appendLine()
                    appendLine("Each result includes:")
                    appendLine("- Library ID: Context7-compatible identifier (format: /org/project)")
                    appendLine("- Name: Library or package name")
                    appendLine("- Description: Short summary")
                    appendLine("- Code Snippets: Number of available code examples")
                    appendLine("- Source Reputation: Authority indicator (High, Medium, Low, or Unknown)")
                    appendLine("- Benchmark Score: Quality indicator (100 is the highest score)")
                    appendLine("- Versions: List of versions if available. Use one of those versions if the user provides a version in their query. The format of the version is /org/project/version.")
                    appendLine()
                    appendLine("For best results, select libraries based on name match, source reputation, snippet coverage, benchmark score, and relevance to your use case.")
                    appendLine()
                    appendLine("----------")
                    appendLine()

                    result.libraries.forEach { library ->
                        appendLine("**${library.name}**")
                        appendLine()
                        appendLine("Library ID: `${library.id}`")
                        if (library.description.isNotBlank()) {
                            appendLine("Description: ${library.description}")
                        }
                        appendLine("Code Snippets: ${library.codeSnippets}")
                        appendLine("Source Reputation: ${library.sourceReputation}")
                        appendLine("Benchmark Score: ${library.benchmarkScore}")
                        if (!library.versions.isNullOrEmpty()) {
                            appendLine("Available Versions: ${library.versions.joinToString(", ")}")
                        }
                        appendLine()
                    }

                    appendLine("**Recommended Selection:**")
                    val topLibrary = result.libraries.maxByOrNull {
                        (it.benchmarkScore * 0.4 + it.codeSnippets * 0.3 + when (it.sourceReputation.lowercase()) {
                            "high" -> 30
                            "medium" -> 20
                            "low" -> 10
                            else -> 0
                        } * 0.3).toInt()
                    }
                    if (topLibrary != null) {
                        appendLine("Based on the search results for '${result.libraryName}', the most relevant library is:")
                        appendLine()
                        appendLine("Library ID: `${topLibrary.id}`")
                        appendLine("Name: ${topLibrary.name}")
                        appendLine("Reasoning: Highest combined score of benchmark (${topLibrary.benchmarkScore}), code snippets (${topLibrary.codeSnippets}), and source reputation (${topLibrary.sourceReputation})")
                    }
                }.trimEnd()
            }
            s.truncateToolResult()
        }

        is Result.Error -> {
            ("Failed to resolve library ID: ${result.error}").truncateToolResult()
        }
    }

    @Serializable
    private data class Context7SearchResponse(
        val results: List<Context7LibraryResult> = emptyList()
    )

    @Serializable
    private data class Context7LibraryResult(
        val id: String,
        val title: String? = null,
        val description: String? = null,
        val branch: String? = null,
        val lastUpdateDate: String? = null,
        val state: String? = null,
        val totalTokens: Int? = null,
        val totalSnippets: Int? = null,
        val stars: Int? = null,
        val trustScore: Double? = null,
        val benchmarkScore: Double? = null,
        val versions: List<String>? = null
    )
}
