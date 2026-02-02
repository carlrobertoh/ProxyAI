package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.ide.util.gotoByName.ChooseByNameModel
import com.intellij.ide.util.gotoByName.GotoClassModel2
import com.intellij.ide.util.gotoByName.GotoFileModel
import com.intellij.ide.util.gotoByName.GotoSymbolModel2
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.nio.file.Paths

/**
 * Enhanced search tool using IntelliJ's native SearchService and FindModel.
 */
class IntelliJSearchTool(
    hookManager: HookManager,
    private val project: Project,
) : BaseTool<IntelliJSearchTool.Args, IntelliJSearchTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "IntelliJSearch",
    description = """
        Search Everywhere-style name search (files, classes, symbols).
        
        Features:
        - Uses the same models as Goto File/Class/Symbol
        - Respects scope (project/all) and de-duplicates results
        - Optimized with NameUtil's MinusculeMatcher like the IDE
        
        Notes:
        - This is a name-oriented search, not content search
        - For content search, use the Grep tool instead
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager
) {

    companion object {
        const val MAX_CONTEXT_CHARS = 200
        const val MAX_OUTPUT_LINES = 100
        const val MAX_OUTPUT_CHARS = 6000
    }

    @Serializable
    data class Args(
        @property:LLMDescription(
            "The text or pattern to search for"
        )
        val pattern: String,

        @property:LLMDescription(
            "Search scope: 'project', 'module', 'directory', 'file', or 'custom'"
        )
        val scope: String? = null,

        @property:LLMDescription(
            "File or directory to search in. If not specified, uses project scope."
        )
        val path: String? = null,

        @property:LLMDescription(
            "File type filter (e.g., 'java', 'kotlin', 'js', 'py')"
        )
        val fileType: String? = null,

        @property:LLMDescription(
            "Search context: 'ANY', 'IN_CODE', 'IN_COMMENTS', 'IN_STRING_LITERALS', 'EXCEPT_COMMENTS', 'EXCEPT_STRING_LITERALS'"
        )
        val context: String? = null,

        @property:LLMDescription(
            "Case sensitive search. Default: false"
        )
        val caseSensitive: Boolean? = null,

        @property:LLMDescription(
            "Regular expression search. Default: false"
        )
        val regex: Boolean? = null,

        @property:LLMDescription(
            "Whole word search. Default: false"
        )
        val wholeWords: Boolean? = null,

        @property:LLMDescription(
            "Output mode: 'content', 'files_with_matches', 'count'"
        )
        val outputMode: String? = null,

        @property:LLMDescription(
            "Maximum number of results to return"
        )
        val limit: Int? = null
    )

    @Serializable
    data class Result(
        val pattern: String,
        val scope: String,
        val totalMatches: Int,
        val matches: List<SearchMatch>,
        val output: String
    )

    @Serializable
    data class SearchMatch(
        val file: String,
        val line: Int?,
        val column: Int?,
        val text: String,
        val context: String?
    )

    override suspend fun doExecute(args: Args): Result {
        try {
            val maxResults = (args.limit ?: 10).coerceIn(1, 50)
            val matches = withTimeout(5000) {
                withContext(Dispatchers.Default) {
                    runReadAction {
                        val scope = createSearchScope(args, project)
                        searchEverywhere(args.pattern, scope, maxResults)
                    }
                }
            }
            val output = formatOutput(matches, args)

            return Result(
                pattern = args.pattern,
                scope = args.scope ?: "project",
                totalMatches = matches.size,
                matches = matches,
                output = output
            )
        } catch (_: TimeoutCancellationException) {
            return Result(
                pattern = args.pattern,
                scope = args.scope ?: "project",
                totalMatches = 0,
                matches = emptyList(),
                output = "Search timed out. Try a more specific pattern or lower scope."
            )
        } catch (e: Exception) {
            return Result(
                pattern = args.pattern,
                scope = args.scope ?: "project",
                totalMatches = 0,
                matches = emptyList(),
                output = "Search failed: ${e.message}"
            )
        }
    }

    override fun createDeniedResult(
        originalArgs: Args,
        deniedReason: String
    ): Result {
        return Result(
            pattern = originalArgs.pattern,
            scope = originalArgs.scope ?: "project",
            totalMatches = 0,
            matches = emptyList(),
            output = deniedReason
        )
    }

    private fun createSearchScope(args: Args, project: Project): GlobalSearchScope {
        return when (args.scope?.lowercase()) {
            "project" -> GlobalSearchScope.projectScope(project)
            "all", "everything", "anywhere" -> GlobalSearchScope.allScope(project)
            "directory" -> {
                val vf = args.path?.let { resolvePath(it) }
                if (vf != null && vf.isDirectory) GlobalSearchScopesCore.directoriesScope(
                    project,
                    true,
                    vf
                )
                else GlobalSearchScope.projectScope(project)
            }

            "file" -> {
                val vf = args.path?.let { resolvePath(it) }
                if (vf != null && !vf.isDirectory) GlobalSearchScope.filesScope(project, listOf(vf))
                else GlobalSearchScope.projectScope(project)
            }

            "module" -> {
                val roots = ProjectRootManager.getInstance(project).contentRoots
                if (roots.isNotEmpty()) {
                    GlobalSearchScopesCore.directoriesScope(project, true, *roots)
                } else GlobalSearchScope.projectScope(project)
            }

            else -> GlobalSearchScope.projectScope(project)
        }
    }

    private fun searchEverywhere(
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int?
    ): List<SearchMatch> {
        val results = mutableListOf<SearchMatch>()
        val max = (limit ?: 10).coerceAtLeast(1)

        val tokens = pattern.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
        val tokenMatchers = if (tokens.isEmpty()) {
            listOf(NameUtil.buildMatcher("*${pattern.trim()}*").build())
        } else {
            tokens.map { t -> NameUtil.buildMatcher("*$t*").build() }
        }

        fun addFromModel(model: ChooseByNameModel) {
            val names: Array<String> = when (model) {
                is GotoFileModel -> model.getNames(true)
                is GotoClassModel2 -> model.getNames(true)
                is GotoSymbolModel2 -> model.getNames(true)
                else -> emptyArray()
            }

            val ranked = names.mapNotNull { name ->
                val degree = tokenMatchers.maxOfOrNull { it.matchingDegree(name) } ?: Int.MIN_VALUE
                if (degree != Int.MIN_VALUE) name to degree else null
            }.sortedByDescending { it.second }

            for ((name, _) in ranked) {
                val elements = when (model) {
                    is GotoFileModel -> model.getElementsByName(name, true, name)
                    is GotoClassModel2 -> model.getElementsByName(name, true, name)
                    is GotoSymbolModel2 -> model.getElementsByName(name, true, name)
                    else -> emptyArray()
                }.filterIsInstance<PsiElement>()

                for (psi in elements) {
                    val target = psi.navigationElement ?: psi
                    val psiFile = target.containingFile
                    val vf = psiFile?.virtualFile
                    if (vf != null && !scope.contains(vf)) continue

                    val offset = when (target) {
                        is PsiNameIdentifierOwner -> target.nameIdentifier?.textOffset
                            ?: target.textOffset

                        else -> target.textOffset
                    }.coerceAtLeast(0)

                    val document =
                        psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
                    val (line, col) = computeLineCol(document, offset)
                    val context = if (psiFile != null) getContextText(psiFile, offset) else null

                    val displayFile = vf?.path ?: (psiFile?.name ?: psi.toString())
                    val svc = project.getService(ProxyAISettingsService::class.java)
                    if (vf?.path != null && svc.isPathIgnored(vf.path)) continue

                    results.add(
                        SearchMatch(
                            file = displayFile,
                            line = line,
                            column = col,
                            text = (target as? PsiNamedElement)?.name ?: name,
                            context = context
                        )
                    )

                    if (results.size >= max) return
                }
            }
        }

        addFromModel(GotoFileModel(project))
        if (results.size >= max) return results.distinctBy { Triple(it.file, it.line, it.column) }
        addFromModel(GotoClassModel2(project))
        if (results.size >= max) return results.distinctBy { Triple(it.file, it.line, it.column) }
        addFromModel(GotoSymbolModel2(project, project))

        return results.distinctBy { Triple(it.file, it.line, it.column) }.take(max)
    }

    private fun computeLineCol(document: Document?, offset: Int): Pair<Int?, Int?> {
        return if (document != null) {
            val line = document.getLineNumber(offset)
            val col = offset - document.getLineStartOffset(line)
            Pair(line + 1, col)
        } else {
            Pair(null, null)
        }
    }

    private fun resolvePath(relativeOrAbsolute: String): VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        if (Paths.get(relativeOrAbsolute).isAbsolute) {
            return fs.findFileByPath(relativeOrAbsolute)
        }
        val base = project.basePath
        if (base != null) {
            val abs = Paths.get(base).resolve(relativeOrAbsolute).normalize().toString()
            fs.findFileByPath(abs)?.let { return it }
        }
        val projDir = guessProjectDir()
        return projDir?.findFileByRelativePath(relativeOrAbsolute)
    }

    private fun guessProjectDir(): VirtualFile? = project.guessProjectDir()

    private fun getContextText(psiFile: PsiFile?, offset: Int): String? {
        if (psiFile == null) return null

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)

        return document.getText(TextRange(lineStart, lineEnd)).trim()
    }

    private fun formatOutput(matches: List<SearchMatch>, args: Args): String {
        val outputMode = args.outputMode?.lowercase() ?: "content"

        return when (outputMode) {
            "files_with_matches" -> {
                val lines = matches.map { it.file }.distinct()
                lines.take(MAX_OUTPUT_LINES).joinToString("\n").truncate(MAX_OUTPUT_CHARS)
            }

            "count" -> {
                val fileCounts = matches.groupBy { it.file }.mapValues { it.value.size }
                val lines = fileCounts.map { "${it.key}: ${it.value}" }
                lines.take(MAX_OUTPUT_LINES).joinToString("\n").truncate(MAX_OUTPUT_CHARS)
            }

            "content" -> {
                val lines = matches.map { match ->
                    val location = if (match.line != null) {
                        "${match.file}:${match.line}"
                    } else {
                        match.file
                    }
                    val context = match.context
                        ?.let {
                            it.condenseWhitespace()
                                .take(MAX_CONTEXT_CHARS) + if (it.length > MAX_CONTEXT_CHARS) "..." else ""
                        }
                        ?.let { " | $it" } ?: ""
                    "$location: ${match.text}$context"
                }
                lines.take(MAX_OUTPUT_LINES).joinToString("\n").truncate(MAX_OUTPUT_CHARS)
            }

            else -> matches.joinToString("\n") { "${it.file}: ${it.text}" }
        }
    }

    override fun encodeResultToString(result: Result): String {
        val raw = result.output
        val lines = if (raw.isEmpty()) emptyList() else raw.lines()
        val limitedLines = lines.take(MAX_OUTPUT_LINES)
        val limitedOutput = limitedLines.joinToString("\n").truncate(MAX_OUTPUT_CHARS)

        val truncatedInfo = buildString {
            val linesTruncated = lines.size - limitedLines.size
            if (linesTruncated > 0) append(" (showing first ${limitedLines.size} lines)")
            val charsTruncated = raw.length - limitedOutput.length
            if (charsTruncated > 0) append(" (truncated $charsTruncated chars)")
        }

        val s = buildString {
            appendLine("Pattern: ${result.pattern}")
            appendLine("Scope: ${result.scope}")
            appendLine("Total matches: ${result.totalMatches}")
            if (result.matches.isNotEmpty()) {
                appendLine()
                appendLine(limitedOutput)
                if (truncatedInfo.isNotEmpty()) appendLine(truncatedInfo.trim())
            } else {
                appendLine("No matches found.")
            }
        }.trimEnd()
        return s.truncateToolResult()
    }
}

private fun String.truncate(max: Int): String =
    if (length <= max) this else this.substring(0, max)

private fun String.condenseWhitespace(): String =
    this.replace("\r", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
