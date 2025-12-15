package ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.toolwindow.agent.ui.AgentUiConfig
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.DiffViewAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import kotlin.math.absoluteValue

object ToolCallDescriptorFactory {

    fun create(
        toolName: String,
        args: Any,
        result: Any? = null,
        projectId: String? = null,
        overrideKind: ToolKind? = null
    ): ToolCallDescriptor {
        val kind = overrideKind ?: detectToolKind(toolName, args)

        return when (kind) {
            ToolKind.SEARCH -> createSearchDescriptor(args, result, projectId)
            ToolKind.READ -> createReadDescriptor(args, result, projectId)
            ToolKind.WRITE -> createWriteDescriptor(args, result, projectId)
            ToolKind.EDIT -> createEditDescriptor(args, result, projectId)
            ToolKind.BASH -> createBashDescriptor(args, result, projectId)
            ToolKind.WEB -> createWebDescriptor(args, result, projectId)
            ToolKind.TASK -> createTaskDescriptor(args, result, projectId)
            ToolKind.LIBRARY_RESOLVE -> createLibraryResolveDescriptor(args, result, projectId)
            ToolKind.LIBRARY_DOCS -> createLibraryDocsDescriptor(args, result, projectId)
            ToolKind.ASK_QUESTION -> createAskDescriptor(args, result, projectId)
            ToolKind.EXIT -> createExitDescriptor(toolName, args, result, projectId)
            ToolKind.OTHER -> createOtherDescriptor(toolName, args, result, projectId)
        }
    }

    private fun detectToolKind(toolName: String, args: Any): ToolKind {
        return when {
            toolName == "IntelliJSearch" || args is IntelliJSearchTool.Args -> ToolKind.SEARCH
            toolName == "Read" || args is ReadTool.Args -> ToolKind.READ
            toolName == "Write" || args is WriteTool.Args -> ToolKind.WRITE
            toolName == "Edit" || args is EditTool.Args -> ToolKind.EDIT
            toolName == "Bash" || args is BashTool.Args -> ToolKind.BASH
            toolName == "BashOutput" || args is BashOutputTool.Args -> ToolKind.BASH
            toolName == "KillShell" || args is KillShellTool.Args -> ToolKind.BASH
            toolName == "WebSearch" || args is WebSearchTool.Args -> ToolKind.WEB
            toolName == "ResolveLibraryId" || args is ResolveLibraryIdTool.Args -> ToolKind.LIBRARY_RESOLVE
            toolName == "GetLibraryDocs" || args is GetLibraryDocsTool.Args -> ToolKind.LIBRARY_DOCS
            toolName == "Task" || args is TaskTool.Args -> ToolKind.TASK
            toolName == "TodoWrite" || toolName == "TodoWriteTool" || args is TodoWriteTool.Args -> ToolKind.TASK
            toolName == "AskUserQuestion" || args is AskUserQuestionTool.Args -> ToolKind.ASK_QUESTION
            toolName == "Exit" -> ToolKind.EXIT
            else -> ToolKind.OTHER
        }
    }

    private fun createAskDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        return ToolCallDescriptor(
            kind = ToolKind.ASK_QUESTION,
            icon = AllIcons.General.ContextHelp,
            titlePrefix = "Clarify Requirements",
            titleMain = "",
            tooltip = "Ask the user clarifying questions",
            supportsStreaming = false,
            args = args,
            result = result,
            projectId = projectId
        )
    }

    private fun createExitDescriptor(
        toolName: String,
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        return ToolCallDescriptor(
            kind = ToolKind.EXIT,
            icon = AllIcons.Actions.Exit,
            titlePrefix = "Exit",
            titleMain = "",
            tooltip = "Agent task completed",
            supportsStreaming = false,
            args = args,
            result = result,
            projectId = projectId
        )
    }

    private fun showTextDialog(content: String, title: String) {
        val dialog = JDialog().apply {
            this.title = title
            isModal = true
        }
        val textArea = JTextArea(content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.smallFont()
        }
        val scrollPane = JScrollPane(textArea).apply {
            preferredSize = JBUI.size(700, 400)
            border = JBUI.Borders.customLine(
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            )
        }
        val footer = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JButton("Copy").apply {
                addActionListener {
                    val selection = StringSelection(content)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        selection,
                        null
                    )
                }
            })
            add(JButton("Close").apply { addActionListener { dialog.dispose() } })
        }
        dialog.contentPane = BorderLayoutPanel().apply {
            add(scrollPane, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    private fun createReadDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val readArgs = args as? ReadTool.Args
        val fileName = extractBaseName(readArgs?.filePath ?: "")

        val lineBadge = when (result) {
            is ReadTool.Result.Success -> Badge("[${result.lineCount} lines]")
            else -> null
        }

        return ToolCallDescriptor(
            kind = ToolKind.READ,
            icon = AllIcons.FileTypes.Text,
            titlePrefix = "Read:",
            titleMain = fileName,
            tooltip = "Read file: ${readArgs?.filePath ?: ""}",
            fileLink = FileLink(
                path = readArgs?.filePath ?: "",
                displayName = fileName,
                enabled = true
            ),
            secondaryBadges = listOfNotNull(lineBadge),
            actions = emptyList(),
            supportsStreaming = false,
            args = args,
            result = result,
            projectId = projectId
        )
    }

    private fun createWriteDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val writeArgs = args as? WriteTool.Args
        val fileName = extractBaseName(writeArgs?.filePath ?: "")

        val badges = mutableListOf<Badge>()
        val actions = mutableListOf<ToolAction>()

        if (result is WriteTool.Result && writeArgs != null) {
            when (result) {
                is WriteTool.Result.Success -> {
                    badges.add(Badge("${result.bytesWritten} bytes", JBColor.GREEN))
                    actions.add(
                        ToolAction("View Changes", AllIcons.Actions.Diff) { component ->
                            DiffViewAction.showDiff(writeArgs.filePath, component)
                        }
                    )
                }


                is WriteTool.Result.Error -> {
                    badges.add(Badge("Error", JBColor.RED))
                }
            }
        }

        return ToolCallDescriptor(
            kind = ToolKind.WRITE,
            icon = AllIcons.FileTypes.Text,
            titlePrefix = "Write:",
            titleMain = fileName,
            tooltip = "Write file: ${writeArgs?.filePath ?: ""}",
            secondaryBadges = badges,
            fileLink = FileLink(
                path = writeArgs?.filePath ?: "",
                displayName = fileName,
                enabled = result != null
            ),
            actions = actions,
            supportsStreaming = false,
            args = args,
            result = result,
            projectId = projectId
        )
    }

    private fun createEditDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val editArgs = args as? EditTool.Args
        val fileName = extractBaseName(editArgs?.filePath ?: "")

        val badges = mutableListOf<Badge>()
        val actions = mutableListOf<ToolAction>()

        if (result is EditTool.Result && editArgs != null) {
            when (result) {
                is EditTool.Result.Success -> {
                    val oldLines = editArgs.oldString.split('\n').size
                    val newLines = editArgs.newString.split('\n').size
                    val changedPer = minOf(oldLines, newLines)
                    val addedPer = (newLines - oldLines).coerceAtLeast(0)
                    val deletedPer = (oldLines - newLines).coerceAtLeast(0)
                    val changed = changedPer * result.replacementsMade
                    val inserted = addedPer * result.replacementsMade
                    val deleted = deletedPer * result.replacementsMade

                    if (changed > 0) badges.add(Badge("[~$changed]", JBColor.YELLOW))
                    if (deleted > 0) badges.add(Badge("[-$deleted]", JBColor.RED))
                    if (inserted > 0) badges.add(Badge("[+$inserted]", JBColor.GREEN))
                    actions.add(
                        ToolAction("View Changes", AllIcons.Actions.Diff) { component ->
                            try {
                                val path = Path.of(editArgs.filePath)
                                val after = Files.readString(path)
                                val before = buildString {
                                    append(after)
                                }.let { cur ->
                                    if (editArgs.replaceAll) {
                                        cur.replace(editArgs.newString, editArgs.oldString)
                                    } else {
                                        replaceFirstNOccurrences(
                                            cur,
                                            editArgs.newString,
                                            editArgs.oldString,
                                            result.replacementsMade
                                        )
                                    }
                                }
                                DiffViewAction.showDiff(
                                    before,
                                    after,
                                    "Changes in ${extractBaseName(editArgs.filePath)}",
                                    null
                                )
                            } catch (_: Exception) {
                                DiffViewAction.showDiff(editArgs.filePath, component)
                            }
                        }
                    )
                }


                is EditTool.Result.Error -> {
                    badges.add(Badge("Error", JBColor.RED))
                }
            }
        }

        return ToolCallDescriptor(
            kind = ToolKind.EDIT,
            icon = AllIcons.Actions.Edit,
            titlePrefix = "Edit:",
            titleMain = fileName,
            tooltip = "Edit file: ${editArgs?.filePath ?: ""}",
            secondaryBadges = badges,
            fileLink = FileLink(
                path = editArgs?.filePath ?: "",
                displayName = fileName,
                enabled = result != null
            ),
            actions = actions,
            supportsStreaming = false,
            args = args,
            result = result,
            projectId = projectId
        )
    }

    private fun replaceFirstNOccurrences(
        input: String,
        target: String,
        replacement: String,
        n: Int
    ): String {
        if (n <= 0 || target.isEmpty()) return input
        var remaining = n
        var idx: Int
        val sb = StringBuilder()
        var cursor = 0
        while (remaining > 0) {
            idx = input.indexOf(target, cursor)
            if (idx < 0) break
            sb.append(input, cursor, idx)
            sb.append(replacement)
            cursor = idx + target.length
            remaining--
        }
        sb.append(input.substring(cursor))
        return sb.toString()
    }

    private fun createBashDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val command = when (args) {
            is BashTool.Args -> args.command
            is BashOutputTool.Args -> args.bashId
            is KillShellTool.Args -> "kill_shell"
            else -> "Unknown"
        }

        val truncatedCommand = truncateCommand(command)

        return ToolCallDescriptor(
            kind = ToolKind.BASH,
            icon = AllIcons.Nodes.Console,
            titlePrefix = "Bash:",
            titleMain = truncatedCommand,
            tooltip = "Command: $command",
            supportsStreaming = true,
            args = args,
            result = result,
            projectId = projectId
        )
    }

    private fun createSearchDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val searchArgs = args as? IntelliJSearchTool.Args
        val pattern = searchArgs?.pattern ?: ""
        val scopeOrPath = searchArgs?.path?.substringAfterLast('/') ?: (searchArgs?.scope ?: "")
        val titleMain = buildSearchDisplay(truncatePattern(pattern), scopeOrPath)

        val badges = mutableListOf<Badge>()
        val actions = mutableListOf<ToolAction>()

        when (result) {
            is IntelliJSearchTool.Result -> {
                badges.add(Badge("${result.totalMatches} matches", JBColor.BLUE))
                if (result.output.isNotBlank()) {
                    actions.add(
                        ToolAction("View Results", AllIcons.Actions.Find) { _ ->
                            showTextDialog(result.output, "Search Results")
                        }
                    )
                }
            }

            is String -> {
                actions.add(
                    ToolAction("View Results", AllIcons.Actions.Find) { _ ->
                        showTextDialog(result, "Search Results")
                    }
                )
            }
        }

        return ToolCallDescriptor(
            kind = ToolKind.SEARCH,
            icon = AllIcons.Actions.Search,
            titlePrefix = "Search:",
            titleMain = titleMain,
            tooltip = buildTooltipString("Search", pattern, scopeOrPath.ifBlank { null }),
            secondaryBadges = badges,
            actions = actions,
            supportsStreaming = false,
            args = args,
            result = result,
            projectId = projectId
        )
    }

    private fun createWebDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val query = when {
            args is WebSearchTool.Args -> args.query
            else -> "Unknown"
        }

        val truncatedQuery = truncateQuery(query)

        return ToolCallDescriptor(
            kind = ToolKind.WEB,
            icon = AllIcons.General.Web,
            titlePrefix = "Web:",
            titleMain = truncatedQuery,
            tooltip = "Web search: $query",
            supportsStreaming = false,
            args = args,
            result = result,
            projectId = projectId
        )
    }

    private fun createTaskDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val description = when (args) {
            is TaskTool.Args -> args.description
            is TodoWriteTool.Args -> todoWriteLabel(args)
            is JsonObject -> extractTodoWriteLabel(args) ?: "Unknown"
            else -> "Unknown"
        }

        val titlePrefix: String
        val prefixColor: JBColor?

        if (args is TaskTool.Args) {
            val subagentType = args.subagentType
            titlePrefix = "[$subagentType]"
            prefixColor = getSubagentColor(subagentType)
        } else {
            titlePrefix = "Task:"
            prefixColor = null
        }

        return ToolCallDescriptor(
            kind = ToolKind.TASK,
            icon = AllIcons.Actions.RunAnything,
            titlePrefix = titlePrefix,
            titleMain = description,
            tooltip = "Task: $description",
            secondaryBadges = emptyList(),
            supportsStreaming = false,
            args = args,
            result = result,
            projectId = projectId,
            prefixColor = prefixColor
        )
    }

    private fun getSubagentColor(subagentType: String): JBColor {
        val hue = subagentType.hashCode().absoluteValue % 360
        val hueNormalized = hue.toFloat() / 360f

        val lightRgb = hslToRgb(hueNormalized, 0.75f, 0.45f)
        val lightColor = Color(lightRgb[0], lightRgb[1], lightRgb[2])

        val darkRgb = hslToRgb(hueNormalized, 0.70f, 0.70f)
        val darkColor = Color(darkRgb[0], darkRgb[1], darkRgb[2])

        return JBColor(lightColor, darkColor)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): IntArray {
        val r: Float
        val g: Float
        val b: Float

        if (s == 0f) {
            r = l
            g = l
            b = l
        } else {
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            r = hueToRgb(p, q, h + 1f / 3f)
            g = hueToRgb(p, q, h)
            b = hueToRgb(p, q, h - 1f / 3f)
        }

        return intArrayOf((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    private fun hueToRgb(p: Float, q: Float, t: Float): Float {
        var t = t
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        if (t < 1f / 6f) return p + (q - p) * 6 * t
        if (t < 1f / 2f) return q
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f
        return p
    }

    private fun todoWriteLabel(args: TodoWriteTool.Args): String {
        val inProgress =
            args.todos.firstOrNull { it.status == TodoWriteTool.TodoStatus.IN_PROGRESS }
        if (inProgress != null && inProgress.activeForm.isNotBlank()) {
            return inProgress.activeForm
        }
        val completed = args.todos.firstOrNull { it.status == TodoWriteTool.TodoStatus.COMPLETED }
        if (completed != null && completed.content.isNotBlank()) {
            return "Marking task done: ${completed.content}"
        }
        if (args.title.isNotBlank()) return "Updating todo list: ${args.title}"
        return "Updating todo list"
    }

    private fun extractTodoWriteLabel(args: JsonObject): String? {
        val todos = args["todos"] as? JsonArray ?: return null
        val inProgress =
            todos.firstOrNull { it.stringValue("status")?.equals("in_progress", true) == true }
        val inProgressLabel = inProgress?.stringValue("activeForm")?.trim().orEmpty()
        if (inProgressLabel.isNotBlank()) return inProgressLabel
        val completed =
            todos.firstOrNull { it.stringValue("status")?.equals("completed", true) == true }
        val completedLabel = completed?.stringValue("content")?.trim().orEmpty()
        if (completedLabel.isNotBlank()) return "Marking task done: $completedLabel"
        val title = args.stringValue("title")?.trim().orEmpty()
        if (title.isNotBlank()) return "Updating todo list: $title"
        return "Updating todo list"
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonArray.firstOrNull(predicate: (JsonObject) -> Boolean): JsonObject? {
        for (element in this) {
            val obj = element as? JsonObject ?: continue
            if (predicate(obj)) return obj
        }
        return null
    }

    private fun createLibraryResolveDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val libraryName = when {
            args is ResolveLibraryIdTool.Args -> args.libraryName
            else -> "Unknown"
        }

        val badges = mutableListOf<Badge>()

        if (result is ResolveLibraryIdTool.Result.Success) {
            badges.add(Badge("${result.libraries.size} found", JBColor.BLUE))
        }

        return ToolCallDescriptor(
            kind = ToolKind.LIBRARY_RESOLVE,
            icon = AllIcons.General.Web,
            titlePrefix = "Library:",
            titleMain = libraryName,
            tooltip = "Resolve library: $libraryName",
            secondaryBadges = badges,
            supportsStreaming = false,
            args = args,
            result = result,
            projectId = projectId
        )
    }

    private fun createLibraryDocsDescriptor(
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        val libraryId = when {
            args is GetLibraryDocsTool.Args -> args.context7CompatibleLibraryID
            else -> "Unknown"
        }

        return ToolCallDescriptor(
            kind = ToolKind.LIBRARY_DOCS,
            icon = AllIcons.General.Web,
            titlePrefix = "Docs:",
            titleMain = libraryId,
            tooltip = "Get library docs: $libraryId",
            supportsStreaming = false,
            args = args,
            result = result,
            projectId = projectId
        )
    }

    private fun createOtherDescriptor(
        toolName: String,
        args: Any,
        result: Any?,
        projectId: String?
    ): ToolCallDescriptor {
        return ToolCallDescriptor(
            kind = ToolKind.OTHER,
            icon = AllIcons.Actions.Help,
            titlePrefix = "Tool:",
            titleMain = toolName,
            tooltip = "Tool: $toolName",
            supportsStreaming = false,
            args = args,
            result = result,
            projectId = projectId
        )
    }

    private fun extractBaseName(filePath: String): String {
        return filePath.substringAfterLast('/')
    }

    private fun truncatePattern(pattern: String): String {
        return if (pattern.length > AgentUiConfig.GREP_PATTERN_MAX) {
            pattern.take(AgentUiConfig.GREP_PATTERN_MAX) + "..."
        } else {
            pattern
        }
    }

    private fun truncateQuery(query: String): String {
        return if (query.length > AgentUiConfig.WEB_QUERY_MAX) {
            query.take(AgentUiConfig.WEB_QUERY_MAX) + "..."
        } else {
            query
        }
    }

    private fun truncateCommand(command: String): String {
        return if (command.length > AgentUiConfig.BASH_CMD_MAX) {
            command.take(AgentUiConfig.BASH_CMD_MAX) + "..."
        } else {
            command
        }
    }

    private fun buildSearchDisplay(pattern: String, scope: String?): String {
        return if (scope.isNullOrBlank()) {
            "\"$pattern\""
        } else {
            "\"$pattern\" in $scope"
        }
    }

    private fun buildTooltipString(operation: String, pattern: String, scope: String?): String {
        return if (scope.isNullOrBlank()) {
            "$operation: \"$pattern\""
        } else {
            "$operation: \"$pattern\" in $scope"
        }
    }
}
