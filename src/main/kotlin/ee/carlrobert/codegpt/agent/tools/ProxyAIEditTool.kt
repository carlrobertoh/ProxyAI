package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.completions.CompletionClientProvider
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.tokens.truncateToolResult
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.llm.client.codegpt.request.AutoApplyRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ProxyAIEditTool(private val project: Project) :
    Tool<ProxyAIEditTool.Args, ProxyAIEditTool.Result>(
        argsSerializer = Args.serializer(),
        resultSerializer = Result.serializer(),
        name = "Edit",
        description = """
        Generates code changes using the ProxyAI auto-apply endpoint.

        Provide an update snippet that describes the edits with surrounding context.
        If there are multiple changes in the same file, include them ALL in a single update_snippet
        (do NOT make multiple Edit tool calls for one file).
        The update snippet must be included in update_snippet using the following format:

        <|update_snippet|>
        // ... existing code ...
        [UPDATED CODE SNIPPET 1]
        // ... existing code ...
        [UPDATED CODE SNIPPET 2]
        // ... existing code ...
        <|/update_snippet|>

        Example (multiple edits in one file):
        <|update_snippet|>
        function add(a, b) {
          // ... existing code ...
          return a + b + 1;
        }
        // ... existing code ...
        const label = "Sum: " + add(1, 2);
        // ... existing code ...
        <|/update_snippet|>

        Parameters:
        - file_path: Absolute path to the file
        - update_snippet: The update snippet in the required format above
        - short_description: Short description of the requested edit.

        Notes:
        - The file must exist and be writable
        - The tool will fail if no changes are produced
    """.trimIndent()
    ) {

    @Serializable
    data class Args(
        @property:LLMDescription(
            "The absolute path to the file to edit. Must be an absolute path, not a relative path."
        )
        @SerialName("file_path")
        val filePath: String,
        @property:LLMDescription(
            "Update snippet containing the edits, formatted with <|update_snippet|> markers."
        )
        @SerialName("update_snippet")
        val updateSnippet: String,
        @property:LLMDescription(
            "Short description of the edit."
        )
        @SerialName("short_description")
        val shortDescription: String
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val filePath: String,
            val originalCode: String,
            val updatedCode: String,
        ) : Result()

        @Serializable
        data class Error(
            val filePath: String,
            val error: String
        ) : Result()
    }

    override suspend fun execute(args: Args): Result {
        return try {
            val svc = project.getService(ProxyAISettingsService::class.java)
            if (svc.isPathIgnored(args.filePath)) {
                return Result.Error(
                    filePath = args.filePath,
                    error = ".proxyai ignore rules block editing this path"
                )
            }

            FileUtil.validateFileForEdit(args.filePath).getOrElse { error ->
                return Result.Error(
                    filePath = args.filePath,
                    error = error.message ?: "File validation failed"
                )
            }

            if (args.updateSnippet.isBlank()) {
                return Result.Error(
                    filePath = args.filePath,
                    error = "update_snippet is empty or missing"
                )
            }

            val normalizedPath = args.filePath.replace("\\", "/")
            val virtualFile = FileUtil.findVirtualFile(normalizedPath)
                ?: return Result.Error(
                    filePath = args.filePath,
                    error = "File not found in IntelliJ VFS: ${args.filePath}"
                )

            val document = withContext(Dispatchers.Default) {
                runReadAction { FileDocumentManager.getInstance().getDocument(virtualFile) }
            } ?: return Result.Error(
                filePath = args.filePath,
                error = "Cannot get document for file: ${args.filePath}"
            )

            val original = document.text

            val model =
                ModelSelectionService.getInstance().getModelForFeature(FeatureType.AUTO_APPLY)
            val updated = withContext(Dispatchers.IO) {
                CompletionClientProvider.getCodeGPTClient()
                    .applyChanges(AutoApplyRequest(model, original, args.updateSnippet))
                    .mergedCode
            }

            when {
                updated.isNullOrBlank() -> Result.Error(
                    filePath = args.filePath,
                    error = "Auto-apply did not return updated content"
                )

                updated == original -> Result.Error(
                    filePath = args.filePath,
                    error = "No changes produced by auto-apply"
                )

                else -> Result.Success(
                    filePath = args.filePath,
                    originalCode = original,
                    updatedCode = updated,
                )
            }
        } catch (e: Exception) {
            Result.Error(
                filePath = args.filePath,
                error = "Failed to edit file: ${e.message}"
            )
        }
    }

    override fun encodeResultToString(result: Result): String = when (result) {
        is Result.Success -> {
            buildString {
                appendLine("Successfully generated edits for '${result.filePath}'")
            }
                .trimEnd()
                .truncateToolResult()
        }

        is Result.Error -> {
            ("Error editing file '${result.filePath}': ${result.error}").truncateToolResult()
        }
    }
}
