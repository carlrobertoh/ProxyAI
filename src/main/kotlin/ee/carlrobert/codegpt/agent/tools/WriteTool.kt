package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

/**
 * Writes content to files, creating new files or overwriting existing ones.
 *
 * Uses IntelliJ's Document API for proper IDE integration with undo/redo support.
 * Handles both project files and external files with appropriate project context.
 */
class WriteTool(
    private val project: Project,
    hookManager: HookManager,
) : BaseTool<WriteTool.Args, WriteTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
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
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager
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

    override suspend fun doExecute(args: Args): Result {
        if (args.content.isBlank()) {
            return Result.Error(
                filePath = args.filePath,
                error = "Content field is required and cannot be empty"
            )
        }

        return try {
            val file = resolveWriteTarget(args.filePath)
            val filePath = file.absolutePath
            val svc = project.service<ProxyAISettingsService>()
            if (svc.isPathIgnored(args.filePath) || svc.isPathIgnored(filePath)) {
                return Result.Error(
                    filePath = filePath,
                    error = "File not found: $filePath"
                )
            }

            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                return Result.Error(
                    filePath = filePath,
                    error = "Failed to create parent directories: ${parent.absolutePath}"
                )
            }

            val isNewFile = !file.exists()

            if (file.exists() && !file.canWrite()) {
                return Result.Error(
                    filePath = filePath,
                    error = "File is not writable: $filePath"
                )
            }

            val bytesWritten = args.content.toByteArray(StandardCharsets.UTF_8).size
            val projectToUse = project

            val normalizedPath = filePath.replace("\\", "/")
            val fileUrl = "file://$normalizedPath"

            // Never do filesystem IO or synchronous VFS refreshes on the EDT.
            // If we can resolve a VirtualFile+Document, use the Document API for proper IDE integration.
            val vfm = VirtualFileManager.getInstance()
            val virtualFile = if (!isNewFile) {
                vfm.findFileByUrl(fileUrl) ?: vfm.refreshAndFindFileByUrl(fileUrl)
            } else {
                vfm.findFileByUrl(fileUrl)
            }

            if (virtualFile != null) {
                val document = withContext(Dispatchers.Default) {
                    runReadAction { FileDocumentManager.getInstance().getDocument(virtualFile) }
                }
                if (document != null) {
                    runInEdt {
                        runWriteAction {
                            document.setText(StringUtil.convertLineSeparators(args.content))
                            PsiDocumentManager.getInstance(projectToUse).commitDocument(document)
                            FileDocumentManager.getInstance().saveDocument(document)
                        }
                    }
                } else {
                    // For non-document-backed files, fall back to plain IO and refresh the specific VirtualFile.
                    withContext(Dispatchers.IO) {
                        file.writeBytes(args.content.toByteArray(StandardCharsets.UTF_8))
                    }
                    VfsUtil.markDirtyAndRefresh(true, false, false, virtualFile)
                }
            } else {
                // Fallback for files not present in VFS (e.g., newly created or external paths).
                withContext(Dispatchers.IO) {
                    file.writeText(
                        StringUtil.convertLineSeparators(args.content),
                        StandardCharsets.UTF_8
                    )
                }

                val lfs = LocalFileSystem.getInstance()
                val parentVf = file.parentFile?.let { parent ->
                    lfs.findFileByIoFile(parent) ?: lfs.refreshAndFindFileByIoFile(parent)
                }
                if (parentVf != null) {
                    // Reload the directory children so the newly created file shows up.
                    VfsUtil.markDirtyAndRefresh(true, false, true, parentVf)
                } else {
                    // As a last resort, refresh just the file path.
                    lfs.refreshAndFindFileByIoFile(file)
                }
            }

            val action = if (isNewFile) "created" else "overwritten"

            Result.Success(
                filePath = filePath,
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

    private fun resolveWriteTarget(requestedPath: String): File {
        val normalized = requestedPath.replace("\\", "/")
        val projectBase = project.basePath ?: return File(normalized)
        val projectPath = Paths.get(projectBase)

        if (!File(normalized).isAbsolute) {
            return projectPath.resolve(normalized).normalize().toFile()
        }

        val relativePath = normalized.removePrefix("/")
        val asProjectRelative = projectPath.resolve(relativePath).normalize().toFile()
        val asAbsolute = File(normalized)

        if (!asAbsolute.exists() && asProjectRelative.parentFile?.exists() == true) {
            return asProjectRelative
        }

        return asAbsolute
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
        is Result.Success -> ("File '${result.filePath}' ${result.message}").truncateToolResult()

        is Result.Error -> ("Error writing file '${result.filePath}': ${result.error}").truncateToolResult()
    }
}
