package ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.Component
import javax.swing.Icon

enum class ToolKind {
    READ, WRITE, EDIT, BASH, BASH_OUTPUT, KILL_SHELL, SEARCH, WEB, TASK, LIBRARY_RESOLVE, LIBRARY_DOCS, ASK_QUESTION, EXIT, OTHER
}

data class Badge(
    val text: String,
    val color: JBColor = JBColor.GRAY,
    val tooltip: String? = null,
    val action: (() -> Unit)? = null
)

data class FileLink(
    val path: String,
    val displayName: String,
    val enabled: Boolean = true,
    val action: ((Project) -> Unit)? = null,
    val line: Int? = null,
    val column: Int? = null
)

data class ToolAction(
    val name: String,
    val icon: Icon,
    val action: (Component) -> Unit
)

data class ToolCallDescriptor(
    val kind: ToolKind,
    val icon: Icon?,
    val titlePrefix: String,
    val titleMain: String,
    val tooltip: String?,
    val secondaryBadges: List<Badge> = emptyList(),
    val fileLink: FileLink? = null,
    val actions: List<ToolAction> = emptyList(),
    val supportsStreaming: Boolean = false,
    val args: Any,
    val result: Any? = null,
    val projectId: String? = null,
    val prefixColor: JBColor? = null,
    val summary: String? = null
)
