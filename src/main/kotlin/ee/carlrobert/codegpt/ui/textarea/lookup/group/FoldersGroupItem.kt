package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.NameUtil
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.action.FolderActionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FoldersGroupItem(
    private val project: Project,
    private val tagManager: TagManager
) : AbstractLookupGroupItem(), DynamicLookupGroupItem {
    private val settingsService = project.service<ProxyAISettingsService>()

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.folders.displayName")
    override val icon = AllIcons.Nodes.Folder

    override suspend fun updateLookupList(lookup: LookupImpl, searchText: String) {
        val folderSuggestions = getLookupItems(searchText)
            .filterIsInstance<FolderActionItem>()
        val existingPaths = lookup.items.mapNotNull { element ->
            (element.getUserData(LookupItem.KEY) as? FolderActionItem)?.folder?.path
        }.toSet()
        val newSuggestions = folderSuggestions
            .filterNot { it.folder.path in existingPaths }

        if (newSuggestions.isEmpty()) {
            return
        }

        withContext(Dispatchers.Default) {
            runInEdt {
                LookupUtil.addLookupItems(
                    lookup,
                    newSuggestions.map { it to 5.0 },
                    searchText = searchText
                )
            }
        }
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        val normalizedSearchText = searchText.trim()
        val matcher = NameUtil.buildMatcher("*$normalizedSearchText").build()

        return getProjectFolders(project)
            .asSequence()
            .filter { folder ->
                settingsService.isVirtualFileVisible(folder) &&
                    !tagManager.containsTag(folder)
            }
            .mapNotNull { folder ->
                if (normalizedSearchText.isEmpty()) {
                    folder to Int.MAX_VALUE
                } else {
                    val matchingDegree = maxOf(
                        matcher.matchingDegree(folder.name),
                        matcher.matchingDegree(folder.path)
                    )
                    if (matchingDegree == Int.MIN_VALUE) {
                        null
                    } else {
                        folder to matchingDegree
                    }
                }
            }
            .sortedWith(
                compareByDescending<Pair<VirtualFile, Int>> { it.second }
                    .thenBy { it.first.path }
            )
            .take(10)
            .map { FolderActionItem(project, it.first) }
            .toList()
    }

    private suspend fun getProjectFolders(project: Project) = withContext(Dispatchers.IO) {
        buildProjectFolders(project)
    }

    private fun buildProjectFolders(project: Project): List<VirtualFile> {
        val folders = mutableListOf<VirtualFile>()
        project.service<ProjectFileIndex>().iterateContent { file: VirtualFile ->
            if (file.isDirectory && !file.name.startsWith(".")) {
                folders.add(file)
            }
            true
        }
        return folders
    }
}
