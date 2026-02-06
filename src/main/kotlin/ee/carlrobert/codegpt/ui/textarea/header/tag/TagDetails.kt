package ee.carlrobert.codegpt.ui.textarea.header.tag

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.mcp.ConnectionStatus
import ee.carlrobert.codegpt.mcp.McpResource
import ee.carlrobert.codegpt.mcp.McpTool
import ee.carlrobert.codegpt.settings.prompts.PersonaDetails
import ee.carlrobert.codegpt.ui.DocumentationDetails
import git4idea.GitCommit
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.util.*
import javax.swing.Icon

@Serializable
sealed class TagDetails(
    val name: String,
    val icon: Icon? = null,
    @Contextual
    val id: UUID = UUID.randomUUID(),
    val createdOn: Long = System.currentTimeMillis(),
    val isRemovable: Boolean = true
) {

    var selected: Boolean = true

    abstract fun getTooltipText(): String?

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TagDetails) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

class EditorTagDetails(val virtualFile: VirtualFile, isRemovable: Boolean = true) :
    TagDetails(virtualFile.name, virtualFile.fileType.icon, isRemovable = isRemovable) {

    private val type: String = "EditorTagDetails"

    override fun getTooltipText(): String = virtualFile.path

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EditorTagDetails

        if (virtualFile != other.virtualFile) return false
        if (type != other.type) return false

        return true
    }

    override fun hashCode(): Int =
        31 * virtualFile.hashCode() + type.hashCode()

}

class FileTagDetails(val virtualFile: VirtualFile) :
    TagDetails(virtualFile.name, virtualFile.fileType.icon) {

    private val type: String = "FileTagDetails"

    override fun getTooltipText(): String = virtualFile.path

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileTagDetails

        if (virtualFile != other.virtualFile) return false
        if (type != other.type) return false

        return true
    }

    override fun hashCode(): Int =
        31 * virtualFile.hashCode() + type.hashCode()
}

data class SelectionTagDetails(
    var virtualFile: VirtualFile,
    var selectionModel: SelectionModel
) : TagDetails(
    "${virtualFile.name} (${selectionModel.selectionStartPosition?.line}:${selectionModel.selectionEndPosition?.line})",
    Icons.InSelection
) {
    var selectedText: String? = selectionModel.selectedText
        private set

    override fun getTooltipText(): String = virtualFile.path
}

class EditorSelectionTagDetails(
    val virtualFile: VirtualFile,
    val selectionModel: SelectionModel
) : TagDetails(
    try {
        "${virtualFile.name} (${selectionModel.selectionStartPosition?.line}:${selectionModel.selectionEndPosition?.line})"
    } catch (e: Exception) {
        virtualFile.name
    },
    virtualFile.fileType.icon
) {
    var selectedText: String? = selectionModel.selectedText
        private set

    override fun getTooltipText(): String = virtualFile.path

    override fun equals(other: Any?): Boolean {
        if (other === null) return false
        return other::class == this::class
    }

    override fun hashCode(): Int {
        return this::class.hashCode()
    }
}

data class DocumentationTagDetails(var documentationDetails: DocumentationDetails) :
    TagDetails(documentationDetails.name, AllIcons.Toolwindows.Documentation) {
    override fun getTooltipText(): String = documentationDetails.url
}

data class PersonaTagDetails(var personaDetails: PersonaDetails) :
    TagDetails(personaDetails.name, AllIcons.General.User) {
    override fun getTooltipText(): String? = null
}

data class GitCommitTagDetails(var gitCommit: GitCommit) :
    TagDetails(gitCommit.id.asString().take(6), AllIcons.Vcs.CommitNode) {
    override fun getTooltipText(): String = gitCommit.fullMessage
}

class CurrentGitChangesTagDetails :
    TagDetails("Current Git Changes", AllIcons.Vcs.CommitNode) {
    override fun getTooltipText(): String? = null
}

data class FolderTagDetails(var folder: VirtualFile) :
    TagDetails(folder.name, AllIcons.Nodes.Folder) {
    override fun getTooltipText(): String = folder.path
}

class WebTagDetails : TagDetails("Web", AllIcons.General.Web) {
    override fun getTooltipText(): String? = null
}

data class McpTagDetails(
    val serverId: String,
    val serverName: String,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val availableTools: List<McpTool> = emptyList(),
    val availableResources: List<McpResource> = emptyList(),
    val lastError: String? = null,
    val serverCommand: String? = null,
    val connectionTime: java.time.Instant? = null,
) : TagDetails(serverName, Icons.MCP) {

    fun getDisplayName(): String {
        val statusSuffix = when (connectionStatus) {
            ConnectionStatus.CONNECTING -> " (connecting...)"
            ConnectionStatus.CONNECTED -> ""
            ConnectionStatus.ERROR -> " (error)"
            ConnectionStatus.DISCONNECTED -> " (disconnected)"
        }
        return serverName + statusSuffix
    }

    override fun getTooltipText(): String {
        val builder = StringBuilder()
        builder.append("<html>")
        builder.append("<body style='width: 300px; font-family: system-ui, sans-serif;'>")

        builder.append("<div style='margin-bottom: 8px;'>")
        builder.append("<b style='font-size: 110%;'>$serverName</b><br>")
        builder.append("<span style='color: #666666; font-size: 90%;'>$serverId</span>")
        builder.append("</div>")

        builder.append("<div style='margin-bottom: 8px; padding: 4px 0; border-top: 1px solid #e0e0e0;'>")
        when (connectionStatus) {
            ConnectionStatus.CONNECTING -> {
                builder.append("⏳ <b>Status:</b> <span style='color: #FDB462;'>Connecting...</span><br>")
                builder.append("<span style='font-size: 90%; color: #666666;'>Establishing connection to MCP server</span>")
            }

            ConnectionStatus.CONNECTED -> {
                builder.append("✅ <b>Status:</b> <span style='color: #4CAF50;'>Connected</span><br>")

                connectionTime?.let {
                    val duration = java.time.Duration.between(it, java.time.Instant.now())
                    val durationStr = when {
                        duration.toHours() > 0 -> "${duration.toHours()}h ${duration.toMinutesPart()}m"
                        duration.toMinutes() > 0 -> "${duration.toMinutes()}m ${duration.toSecondsPart()}s"
                        else -> "${duration.toSeconds()}s"
                    }
                    builder.append("<span style='font-size: 90%; color: #666666;'>Connected for $durationStr</span><br>")
                }

                if (availableTools.isNotEmpty() || availableResources.isNotEmpty()) {
                    builder.append("<div style='margin-top: 6px;'>")
                    if (availableTools.isNotEmpty()) {
                        builder.append("🔧 <b>${availableTools.size} Tool${if (availableTools.size > 1) "s" else ""}</b>")
                        if (availableResources.isNotEmpty()) {
                            builder.append(" • ")
                        }
                    }
                    if (availableResources.isNotEmpty()) {
                        builder.append("📁 <b>${availableResources.size} Resource${if (availableResources.size > 1) "s" else ""}</b>")
                    }
                    builder.append("</div>")

                    if (availableTools.isNotEmpty()) {
                        builder.append("<div style='margin-top: 4px; font-size: 90%; color: #666666;'>")
                        val toolsToShow = availableTools.take(3)
                        builder.append("Tools: ${toolsToShow.joinToString(", ") { it.name }}")
                        if (availableTools.size > 3) {
                            builder.append(" +${availableTools.size - 3} more")
                        }
                        builder.append("</div>")
                    }
                }
            }

            ConnectionStatus.ERROR -> {
                builder.append("❌ <b>Status:</b> <span style='color: #F44336;'>Error</span><br>")
                lastError?.let { error ->
                    builder.append("<div style='margin-top: 4px; padding: 4px; background-color: #FFEBEE; border-radius: 4px;'>")
                    builder.append("<span style='font-size: 90%; color: #B71C1C;'>")
                    val truncatedError = if (error.length > 100) {
                        error.take(100) + "..."
                    } else {
                        error
                    }
                    builder.append(truncatedError)
                    builder.append("</span>")
                    builder.append("</div>")
                }
            }

            ConnectionStatus.DISCONNECTED -> {
                builder.append("⭕ <b>Status:</b> <span style='color: #9E9E9E;'>Disconnected</span><br>")
                builder.append("<span style='font-size: 90%; color: #666666;'>Not connected to MCP server</span>")
            }
        }
        builder.append("</div>")

        serverCommand?.let { cmd ->
            builder.append("<div style='margin-top: 8px; padding-top: 8px; border-top: 1px solid #e0e0e0;'>")
            builder.append("<b style='font-size: 90%;'>Command:</b><br>")
            builder.append("<code style='font-size: 85%; color: #424242;'>")
            // Truncate long commandsx
            val truncatedCmd = if (cmd.length > 50) {
                cmd.take(50) + "..."
            } else {
                cmd
            }
            builder.append(truncatedCmd)
            builder.append("</code>")
            builder.append("</div>")
        }

        builder.append("</body>")
        builder.append("</html>")
        return builder.toString()
    }

    fun getStatusColor(): java.awt.Color {
        return when (connectionStatus) {
            ConnectionStatus.CONNECTING -> JBColor.YELLOW
            ConnectionStatus.CONNECTED -> JBColor(
                java.awt.Color(0, 200, 0),
                java.awt.Color(0, 255, 0)
            )

            ConnectionStatus.ERROR -> JBColor.RED
            ConnectionStatus.DISCONNECTED -> JBColor.GRAY
        }
    }
}

data class ImageTagDetails(val imagePath: String) :
    TagDetails(imagePath.substringAfterLast('/'), AllIcons.FileTypes.Image) {
    override fun getTooltipText(): String = imagePath
}

data class HistoryTagDetails(
    val conversationId: UUID,
    val title: String,
) : TagDetails(title, AllIcons.General.Balloon) {
    override fun getTooltipText(): String? = null
}

class EmptyTagDetails : TagDetails("") {
    override fun getTooltipText(): String? = null
}

class CodeAnalyzeTagDetails : TagDetails("Code Analyze", AllIcons.Actions.DependencyAnalyzer) {
    override fun getTooltipText(): String? = null
}

data class DiagnosticsTagDetails(val virtualFile: VirtualFile) :
    TagDetails("${virtualFile.name} Problems", AllIcons.General.InspectionsEye) {
    override fun getTooltipText(): String = virtualFile.path
}
