package ee.carlrobert.codegpt.settings.hooks

object HookGeneratorExamples {

    val HOOK_GENERATION_EXAMPLES = listOf(
        HookGeneratorOutput(
            event = "afterToolUse",
            scripts = listOf(
                HookScript(
                    name = "notification.sh",
                    language = "sh",
                    content = """#!/bin/bash
# Try multiple approaches to play a notification sound
# Terminal bell
printf '\\a' 2>/dev/null || true

# Check for paplay (Linux)
if command -v paplay > /dev/null 2>&1; then
    paplay /dev/null > /dev/null 2>&1 || true
fi

# Check for afplay (macOS)
if command -v afplay > /dev/null 2>&1; then
    afplay /System/Library/Sounds/Glass.aiff > /dev/null 2>&1 || true
fi

exit 0
""",
                    description = "Plays a notification sound after tool execution"
                )
            ),
            config = HookConfigData(
                command = ".proxyai/hooks/notification.sh",
                timeout = 5,
                enabled = true
            ),
            summary = "Plays a notification sound after tool execution"
        ),
        HookGeneratorOutput(
            event = "beforeShellExecution",
            scripts = listOf(
                HookScript(
                    name = "analytics.sh",
                    language = "sh",
                    content = """#!/bin/bash
# Log shell command execution
echo "$(date): Executing: $0" >> .proxyai/shell-commands.log 2>/dev/null || true
exit 0
""",
                    description = "Logs all shell commands for analytics"
                )
            ),
            config = HookConfigData(
                command = ".proxyai/hooks/analytics.sh",
                timeout = 10,
                enabled = true
            ),
            summary = "Logs all shell command executions for analytics purposes"
        )
    )
}