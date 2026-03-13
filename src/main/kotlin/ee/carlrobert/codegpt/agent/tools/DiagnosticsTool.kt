package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.diagnostics.DiagnosticsFilter
import ee.carlrobert.codegpt.diagnostics.ProjectDiagnosticsService
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.ToolPermissionPolicy
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.tokens.truncateToolResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class DiagnosticsTool(
    private val project: Project,
    private val sessionId: String,
    private val hookManager: HookManager,
) : BaseTool<DiagnosticsTool.Args, DiagnosticsTool.Result>(
    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "Diagnostics",
    description = """
        Reads the IDE's current diagnostics for a specific file.

        Use this tool when you need compiler or inspection diagnostics for one file.
        - The file_path parameter must be an absolute path.
        - filter='errors_only' returns only errors.
        - filter='all' returns errors, warnings, weak warnings, and info diagnostics.
        - Results reflect diagnostics currently available in the IDE for that file.
    """.trimIndent(),
    argsClass = Args::class,
    resultClass = Result::class,
    hookManager = hookManager,
    sessionId = sessionId,
) {

    @Serializable
    data class Args(
        @property:LLMDescription(
            "The absolute path to the file to inspect. Must be an absolute path."
        )
        @SerialName("file_path")
        val filePath: String,
        @property:LLMDescription(
            "Diagnostics filter: 'errors_only' for errors only, or 'all' for all diagnostics."
        )
        val filter: DiagnosticsFilter = DiagnosticsFilter.ERRORS_ONLY
    )

    @Serializable
    data class Result(
        @SerialName("file_path")
        val filePath: String,
        val filter: DiagnosticsFilter,
        @SerialName("diagnostic_count")
        val diagnosticCount: Int = 0,
        val output: String = "",
        val error: String? = null
    )

    override suspend fun doExecute(args: Args): Result {
        val settingsService = project.service<ProxyAISettingsService>()
        val decision = settingsService.evaluateToolPermission(this, args.filePath)
        if (decision == ToolPermissionPolicy.Decision.DENY) {
            return Result(
                filePath = args.filePath,
                filter = args.filter,
                error = "Access denied by permissions.deny for Diagnostics"
            )
        }
        if (settingsService.hasAllowRulesForTool("Diagnostics")
            && decision != ToolPermissionPolicy.Decision.ALLOW
        ) {
            return Result(
                filePath = args.filePath,
                filter = args.filter,
                error = "Access denied by permissions.allow for Diagnostics"
            )
        }
        if (settingsService.isPathIgnored(args.filePath)) {
            return Result(
                filePath = args.filePath,
                filter = args.filter,
                error = "File not found: ${args.filePath}"
            )
        }

        val diagnosticsService = project.service<ProjectDiagnosticsService>()
        val virtualFile = diagnosticsService.findVirtualFile(args.filePath)
            ?: return Result(
                filePath = args.filePath,
                filter = args.filter,
                error = "File not found: ${args.filePath}"
            )

        val report = diagnosticsService.collect(virtualFile, args.filter)
        return Result(
            filePath = args.filePath,
            filter = args.filter,
            diagnosticCount = report.diagnosticCount,
            output = report.content.ifBlank { args.filter.emptyMessage() },
            error = report.error
        )
    }

    override fun createDeniedResult(originalArgs: Args, deniedReason: String): Result {
        return Result(
            filePath = originalArgs.filePath,
            filter = originalArgs.filter,
            error = deniedReason
        )
    }

    override fun encodeResultToString(result: Result): String {
        if (result.error != null) {
            return "Failed to read diagnostics for '${result.filePath}': ${result.error}"
                .truncateToolResult()
        }
        return result.output.truncateToolResult()
    }
}
