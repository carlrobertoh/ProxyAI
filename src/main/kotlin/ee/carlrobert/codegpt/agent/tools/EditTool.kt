package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import ee.carlrobert.codegpt.agent.ToolRunContext
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.hooks.HookEventType
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

class EditTool(
    private val project: Project,
    private val hookManager: HookManager,
    private val sessionId: String? = null
) : BaseTool<EditTool.Args, EditTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "Edit",
    description = """
        Performs an exact string replacement in a file.

        The tool searches for the exact old_string pattern and replaces it with new_string.
        Matching is exact - whitespace, indentation, and all characters must match precisely.

        Parameters:
        - file_path: Absolute path to the file
        - old_string: Exact string to find (case-sensitive, includes whitespace)
        - new_string: Replacement string
        - short_description: Short description of the requested edit.
        - replace_all: If true, replaces ALL occurrences. If false, replaces only the first occurrence

        Important notes:
        - Always verify the old_string matches exactly before attempting edits
        - Use replace_all=true for global replacements
        - The file must exist and be writable
        - Binary files are not supported
        - old_string and new_string must be different
        - Tool will fail if no replacements are made
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager
) {

    private fun getLineAndColumn(document: Document, offset: Int): Pair<Int, Int> {
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        val column = offset - lineStart
        return Pair(line + 1, column)
    }

    private fun getLineContext(document: Document, line: Int, contextLines: Int = 2): String {
        val startLine = maxOf(0, line - contextLines)
        val endLine = minOf(document.lineCount - 1, line + contextLines)
        return buildString {
            for (i in startLine..endLine) {
                val lineStart = document.getLineStartOffset(i)
                val lineEnd = document.getLineEndOffset(i)
                appendLine(document.getText(TextRange(lineStart, lineEnd)))
            }
        }.trim()
    }

    @Serializable
    data class Args(
        @property:LLMDescription(
            "The absolute path to the file to edit. Must be an absolute path, not a relative path."
        )
        @SerialName("file_path")
        val filePath: String,
        @property:LLMDescription(
            "The exact string to search for in the file. Must match exactly including whitespace and indentation."
        )
        @SerialName("old_string")
        val oldString: String,
        @property:LLMDescription(
            "The new string to replace the old string with. Use empty string to delete."
        )
        @SerialName("new_string")
        val newString: String,
        @property:LLMDescription(
            "Short description of the edit."
        )
        @SerialName("short_description")
        val shortDescription: String,
        @property:LLMDescription(
            "Replace all occurrences of old_string with new_string. Default is false (only replace first occurrence)."
        )
        @SerialName("replace_all")
        val replaceAll: Boolean = false
    )

    @Serializable
    data class EditLocation(
        val line: Int,
        val column: Int,
        val context: String
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val filePath: String,
            val replacementsMade: Int,
            val message: String,
            val oldStringPreview: String? = null,
            val newStringPreview: String? = null,
            val editLocations: List<EditLocation> = emptyList()
        ) : Result()

        @Serializable
        data class Error(
            val filePath: String,
            val error: String
        ) : Result()
    }

    override suspend fun doExecute(args: Args): Result {
        return try {
            val svc = project.service<ProxyAISettingsService>()
            if (svc.isPathIgnored(args.filePath)) {
                return Result.Error(
                    filePath = args.filePath,
                    error = ".proxyai ignore rules block editing this path"
                )
            }
            val normalizedPath = args.filePath.replace("\\", "/")
            val file = File(normalizedPath)

            if (!file.exists()) {
                return Result.Error(
                    filePath = args.filePath,
                    error = "File not found: ${args.filePath} (File does not exist on filesystem)"
                )
            }

            if (!file.isFile) {
                return Result.Error(
                    filePath = args.filePath,
                    error = "Path is not a file: ${args.filePath}"
                )
            }

            if (!file.canWrite()) {
                return Result.Error(
                    filePath = args.filePath,
                    error = "File is not writable: ${args.filePath}"
                )
            }

            val virtualFile =
                VirtualFileManager.getInstance().findFileByUrl("file://$normalizedPath")
                    ?: VirtualFileManager.getInstance()
                        .refreshAndFindFileByUrl("file://$normalizedPath")
                    ?: LocalFileSystem.getInstance().findFileByIoFile(file)
                    ?: return Result.Error(
                        filePath = args.filePath,
                        error = "File not found in IntelliJ VFS: ${args.filePath}"
                    )

            if (args.oldString.isEmpty()) {
                return Result.Error(
                    filePath = args.filePath,
                    error = "old_string cannot be empty"
                )
            }

            if (args.oldString == args.newString) {
                return Result.Error(
                    filePath = args.filePath,
                    error = "old_string and new_string are identical. No replacement would be made."
                )
            }

            val document =
                withContext(Dispatchers.Default) {
                    runReadAction {
                        FileDocumentManager.getInstance().getDocument(virtualFile)
                    }
                }
                    ?: return Result.Error(
                        filePath = args.filePath,
                        error = "Cannot get document for file: ${args.filePath}"
                    )

            val content = document.text
            if (!content.contains(args.oldString)) {
                return Result.Error(
                    filePath = args.filePath,
                    error = "String not found in file: '${args.oldString.take(50)}${if (args.oldString.length > 50) "..." else ""}'"
                )
            }

            val editLocations = mutableListOf<EditLocation>()

            if (args.replaceAll) {
                var lastIndex = 0
                while (true) {
                    val index = content.indexOf(args.oldString, lastIndex)
                    if (index == -1) break

                    val (line, column) = getLineAndColumn(document, index)
                    val context = getLineContext(document, line - 1)
                    editLocations.add(EditLocation(line, column, context))
                    lastIndex = index + args.oldString.length
                }
            } else {
                val index = content.indexOf(args.oldString)
                if (index != -1) {
                    val (line, column) = getLineAndColumn(document, index)
                    val context = getLineContext(document, line - 1)
                    editLocations.add(EditLocation(line, column, context))
                }
            }

            val replacementsMade = WriteCommandAction.runWriteCommandAction<Int>(project) {
                var count = 0

                if (args.replaceAll) {
                    var lastIndex = 0
                    while (true) {
                        val index = content.indexOf(args.oldString, lastIndex)
                        if (index == -1) break

                        val range = TextRange(index, index + args.oldString.length)
                        document.replaceString(
                            range.startOffset,
                            range.endOffset,
                            args.newString
                        )
                        count++
                        lastIndex = index + args.newString.length
                    }
                } else {
                    val index = content.indexOf(args.oldString)
                    if (index != -1) {
                        val range = TextRange(index, index + args.oldString.length)
                        document.replaceString(
                            range.startOffset,
                            range.endOffset,
                            args.newString
                        )
                        count = 1
                    }
                }

                FileDocumentManager.getInstance().saveDocument(document)

                count
            }

            if (replacementsMade == 0) {
                return Result.Error(
                    filePath = args.filePath,
                    error = "No replacements made. The old_string and new_string may be identical, or the replacement operation failed."
                )
            }

            val result = Result.Success(
                filePath = args.filePath,
                replacementsMade = replacementsMade,
                message = "Successfully made $replacementsMade replacement${if (replacementsMade != 1) "s" else ""}",
                oldStringPreview = args.oldString.trim().take(200),
                newStringPreview = args.newString.trim().take(200),
                editLocations = editLocations
            )

            val toolId = sessionId?.let { id -> ToolRunContext.getToolId(id) }
            val payload = mapOf(
                "file_path" to args.filePath,
                "replacements_made" to replacementsMade,
                "edit_locations" to editLocations.map { loc ->
                    mapOf(
                        "line" to loc.line,
                        "column" to loc.column
                    )
                }
            )
            val deniedReason = hookManager.checkHooksForDenial(
                HookEventType.AFTER_FILE_EDIT,
                payload,
                "Edit",
                toolId,
                sessionId
            )
            if (deniedReason != null) {
                return Result.Error(
                    filePath = args.filePath,
                    error = deniedReason
                )
            }

            result
        } catch (e: Exception) {
            Result.Error(
                filePath = args.filePath,
                error = "Failed to edit file: ${e.message}"
            )
        }
    }

    override fun createDeniedResult(
        originalArgs: Args,
        deniedReason: String
    ): Result {
        return Result.Error(
            filePath = originalArgs.filePath,
            error = deniedReason
        )
    }

    override fun encodeResultToString(result: Result): String = when (result) {
        is Result.Success -> {
            val raw = buildString {
                appendLine("Successfully edited file '${result.filePath}'")
                appendLine("Made ${result.replacementsMade} replacement${if (result.replacementsMade != 1) "s" else ""}")

                if (result.replacementsMade > 0) {
                    appendLine()
                    appendLine("Changes made:")
                    appendLine("- File: ${result.filePath}")
                    appendLine("- Replacements: ${result.replacementsMade}")

                    if (result.oldStringPreview != null || result.newStringPreview != null) {
                        appendLine()
                        if (result.oldStringPreview != null) {
                            val oldPreview = if (result.oldStringPreview.length > 100) {
                                result.oldStringPreview.take(97) + "..."
                            } else result.oldStringPreview
                            appendLine("- Old text: \"$oldPreview\"")
                        }
                        if (result.newStringPreview != null) {
                            val newPreview = if (result.newStringPreview.length > 100) {
                                result.newStringPreview.take(97) + "..."
                            } else result.newStringPreview
                            appendLine("- New text: \"$newPreview\"")
                        }
                    }
                }
            }.trimEnd()
            raw.truncateToolResult()
        }

        is Result.Error -> {
            ("Error editing file '${result.filePath}': ${result.error}").truncateToolResult()
        }
    }
}
