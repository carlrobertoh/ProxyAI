package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.FolderActionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FoldersGroupItem(
    private val project: Project,
    private val tagManager: TagManager
) : AbstractLookupGroupItem(), DynamicLookupGroupItem {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.folders.displayName")
    override val icon = AllIcons.Nodes.Folder

    override suspend fun updateLookupItems(searchText: String): List<LookupItem> {
        return withContext(Dispatchers.IO) {
            val items = mutableListOf<LookupItem>()
            val tags = tagManager.getTags()
            project.service<ProjectFileIndex>().iterateContent {
                if (it.isDirectory && !it.name.startsWith(".") && 
                    !tags.contains(EditorTagDetails(it)) &&
                    (searchText.isEmpty() || it.name.contains(searchText, ignoreCase = true))
                ) {
                    items.add(FolderActionItem(project, it))
                }
                items.size < 50
            }
            items
        }
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        if (searchText.isEmpty()) {
            return getProjectFolders(project).toFolderSuggestions()
        }
        return getProjectFolders(project)
            .filter { it.path.contains(searchText, ignoreCase = true) }
            .toFolderSuggestions()
    }

    private fun Iterable<VirtualFile>.toFolderSuggestions() =
        take(10).map { FolderActionItem(project, it) }

    private suspend fun getProjectFolders(project: Project) = withContext(Dispatchers.IO) {
        val folders = mutableSetOf<VirtualFile>()
        val tags = tagManager.getTags()
        project.service<ProjectFileIndex>().iterateContent { file: VirtualFile ->
            if (file.isDirectory && !file.name.startsWith(".") && 
                !tags.contains(EditorTagDetails(file))) {
                val folderPath = file.path
                if (folders.none { it.path.startsWith(folderPath) }) {
                    folders.removeAll { it.path.startsWith(folderPath) }
                    folders.add(file)
                }
            }
            true
        }
        folders.toList()
    }
}