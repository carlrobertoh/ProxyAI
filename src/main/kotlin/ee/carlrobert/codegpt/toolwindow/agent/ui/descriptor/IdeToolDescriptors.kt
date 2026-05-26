package ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import ee.carlrobert.codegpt.agent.tools.ide.*

/**
 * IDE-specific tool descriptor factories.
 * These are broken out into a separate file for maintainability given the size of ToolCallDescriptorFactory.
 */
object IdeToolDescriptors {
    fun createRunConfigurationDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val icon = AllIcons.Toolwindows.ToolWindowRun
        val titlePrefix = "Run"

        val badges = mutableListOf<Badge>()
        val actions = mutableListOf<ToolAction>()
        var detailText: String? = null

        when (result) {
            is GetRunConfigurationsTool.Result.Success -> {
                detailText = when (result.totalCount) {
                    0 -> "No run configurations available"
                    else -> "Available run configurations"
                }
                if (result.configurations.isNotEmpty()) {
                    val configList = buildConfigurationsList(result.configurations)
                    actions.add(
                        createShowDialogAction(
                            label = "${result.totalCount} configurations",
                            dialogTitle = "Available Run Configurations"
                        ) { configList }
                    )
                }
            }

            is GetRunConfigurationsTool.Result.Error -> {
                badges.add(Badge("error", JBColor.RED))
                detailText = result.message
                createErrorDialogAction(result.message, "Error Details")?.let(actions::add)
            }
        }

        return ToolCallDescriptor(
            kind = ToolKind.IDE_RUN_CONFIGURATIONS,
            icon = icon,
            titlePrefix = titlePrefix,
            titleMain = "Configurations",
            tooltip = "Available run configurations in project",
            secondaryBadges = badges,
            actions = actions,
            args = args,
            result = result,
            projectId = projectId,
            supportsStreaming = false,
            fileLink = null,
            detailText = detailText
        )
    }

    fun createExecuteRunConfigurationDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val executeArgs = args as? ExecuteRunConfigurationTool.Args
        val configurationName = executeArgs?.configurationName ?: ""
        val normalizedAction = executeArgs?.action?.trim()?.lowercase().orEmpty()

        val badges = mutableListOf<Badge>()
        val actions = mutableListOf<ToolAction>()
        var detailText: String? = null

        when (result) {
            is ExecuteRunConfigurationTool.Result.Success -> {
                val action = result.action.lowercase()
                badges.add(
                    Badge(
                        when (action) {
                            "stop" -> "stopped"
                            else -> "started"
                        },
                        JBColor.GREEN
                    )
                )
                detailText = result.message
            }

            is ExecuteRunConfigurationTool.Result.Error -> {
                badges.add(Badge("error", JBColor.RED))
                detailText = result.message
                createErrorDialogAction(
                    result.message,
                    "Run Configuration Error"
                )?.let(actions::add)
            }
        }

        return ToolCallDescriptor(
            kind = ToolKind.IDE_EXECUTE_RUN_CONFIGURATION,
            icon = AllIcons.Toolwindows.ToolWindowRun,
            titlePrefix = "Run",
            titleMain = configurationName,
            tooltip = "Execute run configuration: $configurationName",
            secondaryBadges = badges,
            actions = actions,
            args = args,
            result = result,
            projectId = projectId,
            detailText = when {
                detailText != null -> detailText
                normalizedAction == "stop" -> "Stop run configuration"
                else -> "Start run configuration"
            }
        )
    }

    private fun buildConfigurationsList(configs: List<GetRunConfigurationsTool.RunConfigurationInfo>): String {
        return buildString {
            appendLine("Available Run Configurations:")
            appendLine()
            configs.forEachIndexed { index, config ->
                append("${index + 1}. ")
                append(config.name)
                append(" (")
                append(config.type)
                if (config.isTemplate) {
                    append(", template")
                }
                appendLine(")")
            }
            appendLine()
            appendLine("Total: ${configs.size} configuration(s)")
        }
    }

    fun createBreakpointDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val bpArgs = args as? BreakpointTool.Args
        val requestedAction = bpArgs?.action?.lowercase().orEmpty()
        val successfulBreakpoint = (result as? BreakpointTool.Result.Success)?.breakpoint
        val filePath = successfulBreakpoint?.filePath ?: bpArgs?.filePath.orEmpty()
        val line = successfulBreakpoint?.line ?: bpArgs?.line

        val badges = mutableListOf<Badge>()
        val actions = mutableListOf<ToolAction>()
        var detailText: String? = null

        when (result) {
            is BreakpointTool.Result.Success -> {
                detailText = formatBreakpointDetail(requestedAction, result.breakpoint)
            }

            is BreakpointTool.Result.Error -> {
                badges.add(Badge("Failed", JBColor.RED))
                detailText = result.message
                createErrorDialogAction(result.message, "Breakpoint Error")?.let(actions::add)
            }
        }

        val fileLink = createFileLink(filePath, line)

        val actionLabel = when (requestedAction) {
            "delete" -> "Remove"
            "edit" -> "Edit"
            else -> "Add"
        }

        return ToolCallDescriptor(
            kind = ToolKind.IDE_BREAKPOINT,
            icon = AllIcons.Debugger.Db_set_breakpoint,
            titlePrefix = "$actionLabel breakpoint:",
            titleMain = "",
            tooltip = if (filePath.isNotEmpty()) "$actionLabel breakpoint at ${filePath}:${line}" else "$actionLabel breakpoint",
            secondaryBadges = badges,
            fileLink = fileLink,
            actions = actions,
            args = args,
            result = result,
            projectId = projectId,
            detailText = detailText
        )
    }

    fun createDebugSessionControlDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val debugArgs = args as? DebugSessionControlTool.Args
        val debugResult = result as? DebugSessionControlTool.Result
        val sessionName = when (debugResult) {
            is DebugSessionControlTool.Result.Success -> debugResult.sessionName
            else -> debugArgs?.sessionName.orEmpty()
        }

        val badges = mutableListOf<Badge>()
        val actions = mutableListOf<ToolAction>()
        var detailText: String? = null

        when (debugResult) {
            is DebugSessionControlTool.Result.Success -> {
                badges.add(Badge(debugResult.afterState.status.name.lowercase(), JBColor.GREEN))
                detailText = formatDebugTransition(
                    debugResult.beforeState,
                    debugResult.afterState,
                    sessionName
                )
                actions.add(
                    createShowDialogAction(
                        label = "State",
                        dialogTitle = "Debug Session"
                    ) {
                        buildString {
                            appendLine(debugResult.message)
                            appendLine()
                            appendLine("Session: ${debugResult.sessionName}")
                            appendLine("Action: ${debugResult.action}")
                            appendLine()
                            appendLine("Before: ${formatDebugState(debugResult.beforeState)}")
                            appendLine("After: ${formatDebugState(debugResult.afterState)}")
                        }
                    }
                )
            }

            is DebugSessionControlTool.Result.Error -> {
                badges.add(Badge("failed", JBColor.RED))
                detailText = debugResult.message
                createErrorDialogAction(
                    debugResult.message,
                    "Debug Session Error"
                )?.let(actions::add)
            }

            null -> Unit
        }

        val currentFile =
            (debugResult as? DebugSessionControlTool.Result.Success)?.afterState?.currentFile
        val currentLine =
            (debugResult as? DebugSessionControlTool.Result.Success)?.afterState?.currentLine
        val fileLink = createFileLink(currentFile, currentLine)

        val actionName = (debugArgs?.action?.replace('_', ' ') ?: "control session")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        return ToolCallDescriptor(
            kind = ToolKind.IDE_DEBUG_SESSION_CONTROL,
            icon = AllIcons.Toolwindows.ToolWindowDebugger,
            titlePrefix = "",
            titleMain = actionName,
            tooltip = "Control IDE debug session",
            secondaryBadges = badges,
            fileLink = fileLink,
            actions = actions,
            args = args,
            result = result,
            projectId = projectId,
            detailText = detailText
        )
    }

    private fun formatDebugState(state: DebugSessionSnapshot): String {
        val status = state.status.name.lowercase()
        val location = if (!state.currentFile.isNullOrBlank()) {
            "${state.currentFile}:${state.currentLine ?: "?"}"
        } else {
            "<unknown location>"
        }
        return "$status at $location"
    }

    private fun formatDebugSessionsContent(result: GetDebugSessionsTool.Result.Success): String {
        return buildString {
            appendLine(result.message)
            appendLine()
            result.sessions.forEach { session ->
                appendLine("Session: ${session.sessionName}")
                appendLine("Status: ${session.status.name.lowercase()}")
                val location = if (!session.currentFile.isNullOrBlank()) {
                    "${session.currentFile}:${session.currentLine ?: "?"}"
                } else {
                    "<unknown location>"
                }
                appendLine("Location: $location")
                session.currentFrame?.takeIf { it.isNotBlank() }?.let {
                    appendLine("Frame: $it")
                }
                if (session.variables.isNotEmpty()) {
                    appendLine("Variables:")
                    session.variables.take(5).forEach { variable ->
                        val value = variable.value?.takeIf { it.isNotBlank() } ?: "<no value>"
                        val type =
                            variable.type?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                        appendLine("- ${variable.name}$type = $value")
                    }
                    if (session.variables.size > 5) {
                        appendLine("- ... ${session.variables.size - 5} more")
                    }
                }
                appendLine()
            }
        }.trimEnd()
    }

    fun createGetBreakpointsDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val badges = mutableListOf<Badge>()
        val actions = mutableListOf<ToolAction>()
        val successResult = result as? GetBreakpointsTool.Result.Success

        when (result) {
            is GetBreakpointsTool.Result.Success -> {
                if (result.breakpoints.isNotEmpty()) {
                    actions.add(
                        createShowDialogAction(
                            label = "${result.totalCount} breakpoints",
                            dialogTitle = "Breakpoints"
                        ) {
                            val content = buildString {
                                appendLine(result.message)
                                appendLine()
                                result.breakpoints.forEach { bp ->
                                    append("- ${bp.filePath}:L${bp.line}")
                                    if (!bp.enabled) {
                                        append(" (disabled)")
                                    }
                                    appendLine()
                                }
                            }
                            content
                        }
                    )
                }
            }

            is GetBreakpointsTool.Result.Error -> {
                badges.add(Badge("error", JBColor.RED))
                createErrorDialogAction(result.message, "Breakpoints Error")?.let(actions::add)
            }
        }

        return ToolCallDescriptor(
            kind = ToolKind.IDE_BREAKPOINTS,
            icon = AllIcons.Debugger.Db_set_breakpoint,
            titlePrefix = "Breakpoints:",
            titleMain = "",
            tooltip = "List breakpoints in project",
            secondaryBadges = badges,
            actions = actions,
            args = args,
            result = result,
            projectId = projectId,
            detailText = when (successResult?.totalCount ?: 0) {
                0 -> "No active breakpoints"
                else -> "Project breakpoint snapshot"
            }
        )
    }

    fun createDebugSessionsDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val sessionResult = result as? GetDebugSessionsTool.Result.Success
        val badges = mutableListOf<Badge>()
        val actions = mutableListOf<ToolAction>()
        var detailText: String? = null
        val pausedCount = sessionResult?.sessions?.count { it.isPaused } ?: 0

        when (result) {
            is GetDebugSessionsTool.Result.Success -> {
                badges.add(
                    Badge(
                        when (result.totalCount) {
                            0 -> "none"
                            1 -> "1 active"
                            else -> "${result.totalCount} active"
                        },
                        JBColor.BLUE
                    )
                )
                if (pausedCount > 0) {
                    badges.add(Badge("$pausedCount paused", JBColor.GREEN))
                }
                detailText = summarizeDebugSessions(
                    result.totalCount,
                    pausedCount,
                    result.sessions.firstOrNull()?.sessionName
                )
                if (result.sessions.isNotEmpty()) {
                    actions.add(
                        createShowDialogAction(
                            label = "${result.totalCount} sessions",
                            dialogTitle = "Debug Sessions"
                        ) {
                            formatDebugSessionsContent(result)
                        }
                    )
                }
            }

            is GetDebugSessionsTool.Result.Error -> {
                badges.add(Badge("error", JBColor.RED))
                detailText = result.message
                createErrorDialogAction(
                    result.message,
                    "Debug Sessions Error"
                )?.let(actions::add)
            }
        }

        val firstWithLocation =
            sessionResult?.sessions?.firstOrNull { !it.currentFile.isNullOrBlank() }
        val fileLink = createFileLink(firstWithLocation?.currentFile, firstWithLocation?.currentLine)

        return ToolCallDescriptor(
            kind = ToolKind.IDE_DEBUG_SESSIONS,
            icon = AllIcons.Toolwindows.ToolWindowDebugger,
            titlePrefix = "",
            titleMain = "Debug sessions",
            tooltip = "Inspect active IDE debug sessions",
            secondaryBadges = badges,
            fileLink = fileLink,
            actions = actions,
            args = args,
            result = result,
            projectId = projectId,
            detailText = detailText
        )
    }

    fun createRunOutputDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val outArgs = args as? GetRunOutputTool.Args
        val name = outArgs?.projectPath ?: "Run"

        val badges = mutableListOf<Badge>()
        val actions = mutableListOf<ToolAction>()
        var detailText: String? = null

        when (result) {
            is GetRunOutputTool.Result.Success -> {
                if (result.activeProcesses > 0) {
                    badges.add(Badge("${result.activeProcesses} active", JBColor.GREEN))
                }
                detailText = if (result.activeProcesses > 0) {
                    "${result.activeProcesses} active process${if (result.activeProcesses == 1) "" else "es"}"
                } else {
                    "No active processes"
                }
                actions.add(
                    ToolAction(
                        "${result.processes.size} processes",
                        AllIcons.Actions.Show
                    ) {
                        val content = buildString {
                            appendLine(result.message)
                            appendLine()
                            if (result.processes.isNotEmpty()) {
                                appendLine("Processes:")
                                result.processes.forEach { p ->
                                    appendLine("- ${p.configurationName ?: "Unknown"} — ${if (p.isRunning) "running" else "exited: ${p.exitCode}"}")
                                }
                            }
                        }
                        ToolCallDescriptorFactory.showTextDialog(content, "Run Output: $name")
                    }
                )
            }

            is GetRunOutputTool.Result.Error -> {
                badges.add(Badge("error", JBColor.RED))
                detailText = result.message
                createErrorDialogAction(result.message, "Error Details")?.let(actions::add)
            }
        }

        return ToolCallDescriptor(
            kind = ToolKind.IDE_RUN_OUTPUT,
            icon = AllIcons.RunConfigurations.TestState.Run,
            titlePrefix = "Output",
            titleMain = name,
            tooltip = "Captured run output for $name",
            secondaryBadges = badges,
            actions = actions,
            args = args,
            result = result,
            projectId = projectId,
            detailText = detailText
        )
    }

    private fun formatBreakpointDetail(
        action: String,
        breakpoint: BreakpointTool.BreakpointDetails
    ): String {
        val location = "${breakpoint.filePath.substringAfterLast('/')}:L${breakpoint.line}"
        return when (action) {
            "delete" -> "Breakpoint removed at $location"
            "edit" -> breakpoint.condition?.takeIf { it.isNotBlank() }
                ?.let { "Condition updated: $it" }
                ?: "Breakpoint updated at $location"

            else -> breakpoint.condition?.takeIf { it.isNotBlank() }
                ?.let { "Breakpoint created with condition: $it" }
                ?: "Breakpoint created at $location"
        }
    }

    private fun createFileLink(path: String?, line: Int?): FileLink? {
        if (path.isNullOrBlank()) {
            return null
        }

        return FileLink(
            path = path,
            displayName = formatFileDisplayName(path, line),
            enabled = true,
            line = line
        )
    }

    private fun formatFileDisplayName(path: String, line: Int?): String {
        return buildString {
            append(path.substringAfterLast('/'))
            line?.let { append(":L$it") }
        }
    }

    private fun createShowDialogAction(
        label: String,
        dialogTitle: String,
        content: () -> String
    ): ToolAction {
        return ToolAction(label, AllIcons.Actions.Show) {
            ToolCallDescriptorFactory.showTextDialog(content(), dialogTitle)
        }
    }

    private fun createErrorDialogAction(
        message: String,
        dialogTitle: String
    ): ToolAction? {
        if (message.isBlank()) {
            return null
        }

        return ToolAction("Error", AllIcons.General.Error) {
            ToolCallDescriptorFactory.showTextDialog(message, dialogTitle)
        }
    }

    private fun formatDebugTransition(
        before: DebugSessionSnapshot,
        after: DebugSessionSnapshot,
        sessionName: String,
    ): String {
        val beforeLocation = formatShortLocation(before)
        val afterLocation = formatShortLocation(after)
        return when {
            after.status == DebugSessionStatus.STOPPED -> {
                listOfNotNull(sessionName.takeIf { it.isNotBlank() }, "$beforeLocation -> stopped")
                    .joinToString(", ")
            }

            beforeLocation != afterLocation -> {
                listOfNotNull(
                    sessionName.takeIf { it.isNotBlank() },
                    "$beforeLocation -> $afterLocation",
                    after.status.name.lowercase()
                )
                    .joinToString(", ")
            }

            before.status != after.status -> {
                listOfNotNull(
                    sessionName.takeIf { it.isNotBlank() },
                    "${before.status.name.lowercase()} -> ${after.status.name.lowercase()}"
                )
                    .joinToString(", ")
            }

            else -> {
                listOfNotNull(
                    sessionName.takeIf { it.isNotBlank() },
                    "$afterLocation, ${after.status.name.lowercase()}"
                )
                    .joinToString(", ")
            }
        }
    }

    private fun formatShortLocation(state: DebugSessionSnapshot): String {
        return if (!state.currentFile.isNullOrBlank() && state.currentLine != null) {
            "${state.currentFile.substringAfterLast('/')}:L${state.currentLine}"
        } else {
            state.status.name.lowercase()
        }
    }

    private fun summarizeDebugSessions(
        totalCount: Int,
        pausedCount: Int,
        firstSessionName: String?
    ): String {
        return when {
            totalCount == 0 -> "No active debug sessions"
            totalCount == 1 && pausedCount == 1 -> "${firstSessionName ?: "1 session"} paused"
            totalCount == 1 -> "${firstSessionName ?: "1 session"} running"
            pausedCount > 0 -> "$totalCount active, $pausedCount paused"
            else -> "$totalCount active"
        }
    }
}
