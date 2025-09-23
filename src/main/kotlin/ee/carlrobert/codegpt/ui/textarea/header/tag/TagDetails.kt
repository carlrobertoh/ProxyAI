package ee.carlrobert.codegpt.ui.textarea.header.tag

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.settings.prompts.PersonaDetails
import ee.carlrobert.codegpt.ui.DocumentationDetails
import git4idea.GitCommit
import java.util.*
import javax.swing.Icon

sealed class TagDetails(
    val name: String,
    val icon: Icon? = null,
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
    override fun getTooltipText(): String? = documentationDetails.url
}

data class PersonaTagDetails(var personaDetails: PersonaDetails) :
    TagDetails(personaDetails.name, AllIcons.General.User) {
    override fun getTooltipText(): String? = null
}

data class GitCommitTagDetails(var gitCommit: GitCommit) :
    TagDetails(gitCommit.id.asString().take(6), AllIcons.Vcs.CommitNode) {
    override fun getTooltipText(): String? = gitCommit.fullMessage
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
