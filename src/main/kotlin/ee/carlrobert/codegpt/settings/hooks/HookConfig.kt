package ee.carlrobert.codegpt.settings.hooks

import kotlinx.serialization.Serializable

@Serializable
data class HookConfig(
    val command: String,
    val matcher: String? = null,
    val timeout: Int? = null,
    val loopLimit: Int? = null,
    val enabled: Boolean = true
)

@Serializable
data class HookConfiguration(
    val beforeToolUse: MutableList<HookConfig> = mutableListOf(),
    val afterToolUse: MutableList<HookConfig> = mutableListOf(),
    val subagentStart: MutableList<HookConfig> = mutableListOf(),
    val subagentStop: MutableList<HookConfig> = mutableListOf(),
    val beforeShellExecution: MutableList<HookConfig> = mutableListOf(),
    val afterShellExecution: MutableList<HookConfig> = mutableListOf(),
    val beforeReadFile: MutableList<HookConfig> = mutableListOf(),
    val afterFileEdit: MutableList<HookConfig> = mutableListOf(),
    val stop: MutableList<HookConfig> = mutableListOf(),
) {
    fun hooksFor(event: HookEventType): List<HookConfig> {
        return when (event) {
            HookEventType.BEFORE_TOOL_USE -> beforeToolUse
            HookEventType.AFTER_TOOL_USE -> afterToolUse
            HookEventType.SUBAGENT_START -> subagentStart
            HookEventType.SUBAGENT_STOP -> subagentStop
            HookEventType.BEFORE_BASH_EXECUTION -> beforeShellExecution
            HookEventType.AFTER_BASH_EXECUTION -> afterShellExecution
            HookEventType.BEFORE_READ_FILE -> beforeReadFile
            HookEventType.AFTER_FILE_EDIT -> afterFileEdit
            HookEventType.STOP -> stop
        }
    }
}
