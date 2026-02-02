package ee.carlrobert.codegpt.settings.hooks

enum class HookEventType(val eventName: String) {
    BEFORE_TOOL_USE("beforeToolUse"),
    AFTER_TOOL_USE("afterToolUse"),
    SUBAGENT_START("subagentStart"),
    SUBAGENT_STOP("subagentStop"),
    BEFORE_BASH_EXECUTION("beforeShellExecution"),
    AFTER_BASH_EXECUTION("afterShellExecution"),
    BEFORE_READ_FILE("beforeReadFile"),
    AFTER_FILE_EDIT("afterFileEdit"),
    STOP("stop");

    companion object {
        fun fromString(name: String): HookEventType? =
            entries.find { it.eventName == name }
    }
}