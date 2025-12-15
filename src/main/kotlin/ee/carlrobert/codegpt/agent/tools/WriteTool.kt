package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Writes content to files, creating new files or overwriting existing ones.
 *
 * Uses IntelliJ's Document API for proper IDE integration with undo/redo support.
 * Handles both project files and external files with appropriate project context.
 */
class WriteTool(private val project: Project) : Tool<WriteTool.Args, WriteTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "Write",
    description = """
        Writes content to a file, creating it if it doesn't exist or overwriting if it does.

        This tool creates parent directories if they don't exist and handles both
        new file creation and existing file overwrites.

        Parameters:
        - file_path: Absolute path to the file to write (required)
        - content: The content to write to the file (required, cannot be empty)

        Important notes:
        - Always use absolute paths
        - Parent directories are created automatically
        - Existing files will be overwritten completely
        - Uses UTF-8 encoding by default
        - Binary content should be handled differently
        - Content field is required and cannot be empty
    """.trimIndent()
) {

    @Serializable
    data class Args(
        @property:LLMDescription(
            "The absolute path to the file to write. Must be an absolute path, not a relative path."
        )
        @SerialName("file_path")
        val filePath: String = "",
        @property:LLMDescription(
            "The content to write to the file. Must not be empty."
        )
        val content: String = ""
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val filePath: String,
            val bytesWritten: Int,
            val isNewFile: Boolean,
            val message: String
        ) : Result()

        @Serializable
        data class Error(
            val filePath: String,
            val error: String
        ) : Result()
    }

    override suspend fun execute(args: Args): Result {
        val svc = project.getService(ProxyAISettingsService::class.java)
        if (svc.isPathIgnored(args.filePath)) {
            return Result.Error(
                filePath = args.filePath,
                error = ".proxyai ignore rules block writing to this path"
            )
        }
        if (args.content.isBlank()) {
            return Result.Error(
                filePath = args.filePath,
                error = "Content field is required and cannot be empty"
            )
        }
        
        return try {
            val file = File(args.filePath)

            file.parentFile?.mkdirs()

            val isNewFile = !file.exists()

            if (file.exists() && !file.canWrite()) {
                return Result.Error(
                    filePath = args.filePath,
                    error = "File is not writable: ${args.filePath}"
                )
            }

            val virtualFile =
                VirtualFileManager.getInstance().findFileByUrl("file://${args.filePath}")
                    ?: if (isNewFile) {
                        VirtualFileManager.getInstance().findFileByUrl("file://${args.filePath}")
                    } else {
                        null
                    }

            val bytesWritten = args.content.toByteArray(StandardCharsets.UTF_8).size
            val projectToUse = project

            if (virtualFile != null) {
                val document =
                    runReadAction { FileDocumentManager.getInstance().getDocument(virtualFile) }
                runInEdt {
                    if (document != null) {
                        runWriteAction {
                            PsiDocumentManager.getInstance(projectToUse).commitDocument(document)
                            document.setText(StringUtil.convertLineSeparators(args.content))
                            FileDocumentManager.getInstance().saveDocument(document)
                        }
                    } else {
                        runWriteAction {
                            virtualFile.setBinaryContent(args.content.toByteArray(StandardCharsets.UTF_8))
                        }
                    }
                }
            } else if (isNewFile) {
                runInEdt {
                    runWriteAction {
                        file.writeText(
                            StringUtil.convertLineSeparators(args.content),
                            StandardCharsets.UTF_8
                        )
                        VirtualFileManager.getInstance().syncRefresh()
                    }
                }
            }

            val action = if (isNewFile) "created" else "overwritten"

            Result.Success(
                filePath = args.filePath,
                bytesWritten = bytesWritten,
                isNewFile = isNewFile,
                message = "File $action successfully. $bytesWritten bytes written."
            )

        } catch (e: Exception) {
            Result.Error(
                filePath = args.filePath,
                error = "Failed to write file: ${e.message}"
            )
        }
    }

    override fun encodeResultToString(result: Result): String = when (result) {
        is Result.Success -> ("File '${result.filePath}' ${result.message}").truncateToolResult()

        is Result.Error -> ("Error writing file '${result.filePath}': ${result.error}").truncateToolResult()
    }
}
