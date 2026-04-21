package ee.carlrobert.codegpt.toolwindow.agent

import ee.carlrobert.codegpt.agent.ToolName
import ee.carlrobert.codegpt.agent.ToolSpecs
import ee.carlrobert.codegpt.agent.tools.EditTool
import ee.carlrobert.codegpt.agent.tools.ReadTool
import ee.carlrobert.codegpt.agent.tools.WriteTool
import kotlinx.serialization.json.Json

internal object HistoricalRollbackCompatibility {

    private val supportedTools = setOf(ToolName.READ, ToolName.EDIT, ToolName.WRITE)

    fun resolveSupportedTool(rawToolName: String): ToolName? {
        val normalized = rawToolName.trim()
        if (normalized.isEmpty()) return null

        val resolved = ToolSpecs.find(normalized)?.name ?: return null
        return resolved.takeIf { it in supportedTools }
    }

    fun isSuccessfulResult(toolName: ToolName, content: String, replayJson: Json): Boolean {
        if (toolName !in supportedTools) return false

        val normalized = content.trim()
        val decoded = ToolSpecs.decodeResultOrNull(toolName.id, normalized)
        if (decoded != null) {
            return when (toolName) {
                ToolName.READ -> decoded is ReadTool.Result.Success
                ToolName.EDIT -> decoded is EditTool.Result.Success
                ToolName.WRITE -> decoded is WriteTool.Result.Success
                else -> false
            }
        }

        // TODO: Is there a better way to determine if the result is successful?
        // This string comparison won't cut it
        return when (toolName) {
            ToolName.READ -> !normalized.startsWith(READ_ERROR_PREFIX, ignoreCase = true)
            ToolName.EDIT ->
                normalized.isNotBlank() &&
                    !normalized.startsWith(EDIT_ERROR_PREFIX, ignoreCase = true) &&
                    (normalized.contains(EDIT_SUCCESS_MARKER, ignoreCase = true) ||
                        normalized.contains(LEGACY_EDIT_SUCCESS_MARKER, ignoreCase = true))
            ToolName.WRITE ->
                normalized.isNotBlank() &&
                    normalized.contains(WRITE_SUCCESS_MARKER, ignoreCase = true) &&
                    !normalized.startsWith(WRITE_ERROR_PREFIX, ignoreCase = true)
            else -> false
        }
    }

    private const val READ_ERROR_PREFIX = "Error reading file"
    private const val EDIT_ERROR_PREFIX = "Error editing file"
    private const val WRITE_ERROR_PREFIX = "Error writing file"
    private const val EDIT_SUCCESS_MARKER = "Successfully edited file"
    private const val LEGACY_EDIT_SUCCESS_MARKER = "Successfully made"
    private const val WRITE_SUCCESS_MARKER = "successfully"
}
