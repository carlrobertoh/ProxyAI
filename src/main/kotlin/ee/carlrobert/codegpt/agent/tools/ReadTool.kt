package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets

/**
 * Reads a file from the local filesystem using IntelliJ's Document and VirtualFile APIs.
 */
class ReadTool(private val project: Project) : Tool<ReadTool.Args, ReadTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "Read",
    description = """
        Reads a file from the local filesystem. You can access any file directly by using this tool.
        Assume this tool is able to read all files on the machine. If the user provides a path to a file, assume that path is valid.

        By default, reads up to 2000 lines starting from the beginning of the file.
        You can optionally specify a line offset and limit to read specific sections.
        Any lines longer than 2000 characters will be truncated.

        Results are returned using cat -n format, with line numbers starting at 1.
        This tool can read images (eg PNG, JPG, etc), PDF files, and Jupyter notebooks.

        - The file_path parameter must be an absolute path
        - For large files, use offset and limit to read in chunks
        - Images are returned visually for analysis
        - PDFs are processed page by page
        - Jupyter notebooks return all cells with outputs
    """.trimIndent()
) {

    companion object {
        private val logger = thisLogger()
    }

    @Serializable
    data class Args(
        @property:LLMDescription(
            "The absolute path to the file to read. Must be an absolute path, not a relative path."
        )
        @SerialName("file_path")
        val filePath: String,
        @property:LLMDescription(
            "The line number to start reading from (1-indexed). Only provide if the file is too large to read at once."
        )
        val offset: Int? = null,
        @property:LLMDescription(
            "The number of lines to read. Only provide if the file is too large to read at once."
        )
        val limit: Int? = null
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val filePath: String,
            val content: String,
            val lineCount: Int,
            val truncated: Boolean,
            val fileType: String?,
            val startLine: Int? = null,
            val endLine: Int? = null
        ) : Result()

        @Serializable
        data class Error(
            val filePath: String,
            val error: String
        ) : Result()
    }

    override suspend fun execute(args: Args): Result {
        return try {
            return withContext(Dispatchers.Default) { runReadAction {
                val svc = project.getService(ProxyAISettingsService::class.java)
                if (svc.isPathIgnored(args.filePath)) {
                    return@runReadAction Result.Error(
                        filePath = args.filePath,
                        error = "Access to this path is blocked by .proxyai ignore rules"
                    )
                }

                val virtualFile =
                    VirtualFileManager.getInstance().findFileByUrl("file://${args.filePath}")
                        ?: return@runReadAction Result.Error(
                            filePath = args.filePath,
                            error = "File not found: ${args.filePath}"
                        )

                if (virtualFile.isDirectory) {
                    return@runReadAction Result.Error(
                        filePath = args.filePath,
                        error = "Path is a directory, not a file: ${args.filePath}"
                    )
                }

                val fileType = FileTypeManager.getInstance().getFileTypeByFile(virtualFile)

                when {
                    fileType.isBinary -> {
                        Result.Error(
                            filePath = args.filePath,
                            error = "Binary files are not supported by ProxyAI yet."
                        )
                    }

                    else -> {
                        val content = try {
                            val document =
                                FileDocumentManager.getInstance().getDocument(virtualFile)
                            document?.text ?: String(
                                virtualFile.contentsToByteArray(),
                                StandardCharsets.UTF_8
                            )
                        } catch (e: Exception) {
                            logger.error("Error reading document from $virtualFile", e)
                            String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
                        }

                        val lines = content.lines()
                        val totalLines = lines.size

                        val (startIdx, endIdx, truncated) = when {
                            args.offset != null && args.limit != null -> {
                                val start = (args.offset - 1).coerceIn(0, totalLines)
                                val end = (start + args.limit).coerceIn(start, totalLines)
                                Triple(start, end, end < totalLines)
                            }

                            args.offset != null -> {
                                val start = (args.offset - 1).coerceIn(0, totalLines)
                                Triple(start, totalLines, false)
                            }

                            args.limit != null -> {
                                Triple(
                                    0,
                                    args.limit.coerceIn(0, totalLines),
                                    totalLines > args.limit
                                )
                            }

                            else -> {
                                val limit = 2000
                                Triple(0, limit.coerceIn(0, totalLines), totalLines > limit)
                            }
                        }

                        val startLine = if (startIdx == 0) null else startIdx + 1
                        val endLine = if (endIdx == totalLines) null else endIdx

                        val selectedLines = lines.subList(startIdx, endIdx)
                        val numberedContent = buildString {
                            selectedLines.forEachIndexed { index, line ->
                                val lineNumber = startIdx + index + 1
                                appendLine("${lineNumber}\t${line.take(2000)}")
                            }
                        }

                        Result.Success(
                            filePath = args.filePath,
                            content = numberedContent.trimEnd(),
                            lineCount = selectedLines.size,
                            truncated = truncated,
                            fileType = fileType.name,
                            startLine = startLine,
                            endLine = endLine
                        )
                    }
                }
            }}
        } catch (e: Exception) {
            Result.Error(
                filePath = args.filePath,
                error = "Failed to read file: ${e.message}"
            )
        }
    }

    override fun encodeResultToString(result: Result): String = when (result) {
        is Result.Success -> {
            val raw = buildString {
                if (result.truncated) {
                    appendLine("Note: Content truncated. Use offset parameter to read more lines.")
                    appendLine()
                }
                append(result.content)
            }.trimEnd()
            raw.truncateToolResult()
        }

        is Result.Error -> {
            ("Error reading file '${result.filePath}': ${result.error}").truncateToolResult()
        }
    }
}
