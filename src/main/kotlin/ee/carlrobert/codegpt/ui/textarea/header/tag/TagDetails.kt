package ee.carlrobert.codegpt.ui.textarea.header.tag

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.Icons.Tree
import ee.carlrobert.codegpt.settings.prompts.PersonaDetails
import ee.carlrobert.codegpt.ui.DocumentationDetails
import git4idea.GitCommit
import java.util.*
import javax.swing.Icon

sealed class TagDetails(
    val name: String,
    val icon: Icon? = null,
    var selected: Boolean = true,
    val id: UUID = UUID.randomUUID()
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TagDetails) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

class PsiStructureTagDetails(val tokenCount: Int) :
    TagDetails("AST structure", Tree, id = TAG_UUID) {

    private companion object {
        val TAG_UUID: UUID = UUID.randomUUID()
    }
}

data class FileTagDetails(var virtualFile: VirtualFile) :
    TagDetails(virtualFile.name, virtualFile.fileType.icon)

data class SelectionTagDetails(
    var virtualFile: VirtualFile,
    var selectionModel: SelectionModel
) : TagDetails(
    "${virtualFile.name} (${selectionModel.selectionStartPosition?.line}:${selectionModel.selectionEndPosition?.line})",
    Icons.InSelection
) {
    var selectedText: String? = selectionModel.selectedText
        private set
}

data class DocumentationTagDetails(var documentationDetails: DocumentationDetails) :
    TagDetails(documentationDetails.name, AllIcons.Toolwindows.Documentation)

data class PersonaTagDetails(var personaDetails: PersonaDetails) :
    TagDetails(personaDetails.name, AllIcons.General.User)

data class GitCommitTagDetails(var gitCommit: GitCommit) :
    TagDetails(gitCommit.id.asString().take(6), AllIcons.Vcs.CommitNode)

class CurrentGitChangesTagDetails :
    TagDetails("Current Git Changes", AllIcons.Vcs.CommitNode)

data class FolderTagDetails(var folder: VirtualFile) :
    TagDetails(folder.name, AllIcons.Nodes.Folder)

class WebTagDetails : TagDetails("Web", AllIcons.General.Web)

class EmptyTagDetails : TagDetails("")