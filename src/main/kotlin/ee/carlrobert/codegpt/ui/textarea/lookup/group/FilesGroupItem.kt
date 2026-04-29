package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.codeStyle.MinusculeMatcher
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.ui.textarea.FileSearchCandidate
import ee.carlrobert.codegpt.ui.textarea.FileSearchProvider
import ee.carlrobert.codegpt.ui.textarea.FileSearchSource
import ee.carlrobert.codegpt.ui.textarea.NativeFileSearchProvider
import ee.carlrobert.codegpt.ui.textarea.isHiddenFileOrInHiddenDirectory
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupMatchers
import ee.carlrobert.codegpt.ui.textarea.lookup.action.FolderActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.IncludeOpenFilesActionItem

class FilesGroupItem(
    private val project: Project,
    private val tagManager: TagManager,
    private val fileSearchProvider: FileSearchProvider = NativeFileSearchProvider(project)
) : AbstractLookupGroupItem(), DynamicLookupGroupItem {
    private val settingsService = project.service<ProxyAISettingsService>()
    @Volatile
    private var cachedFolders: List<VirtualFile> = emptyList()
    @Volatile
    private var cachedFolderStructureModCount: Long = -1L
    @Volatile
    private var cachedFolderRootModCount: Long = -1L

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.files.displayName")
    override val icon = AllIcons.FileTypes.Any_type

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        val normalizedSearchText = searchText.trim()
        val openFiles = getOpenFileCandidates(normalizedSearchText)
        val providerMatches = fileSearchProvider.search(normalizedSearchText, MAX_SEARCH_FILES)
        val visibleProviderMatches = readAction {
            val projectFileIndex = project.service<ProjectFileIndex>()
            providerMatches.filter { candidate ->
                isVisibleProjectItem(candidate.file, projectFileIndex)
            }
        }
        val providerFiles = visibleProviderMatches.filterNot { it.file.isDirectory }
        val folderItems = getFolderSuggestions(normalizedSearchText)

        val orderedCandidates = if (normalizedSearchText.isEmpty()) {
            buildDefaultCandidates(
                openFiles = openFiles,
                recentFiles = getRecentFileCandidates(normalizedSearchText)
            )
        } else {
            val recentFiles = if (openFiles.isEmpty()) {
                getRecentFileCandidates(normalizedSearchText)
            } else {
                emptyList()
            }
            buildList {
                addAll(providerFiles)
                addAll(openFiles)
                addAll(recentFiles)
            }.distinctBy { it.file.path }
        }

        return orderedCandidates.toFileSuggestions(normalizedSearchText) + folderItems
    }

    companion object {
        private const val MAX_SEARCH_FILES = 200
        private const val MAX_DEFAULT_FILE_SUGGESTIONS = 25
        private const val MAX_SEARCH_FOLDERS = 200
        private const val MAX_DEFAULT_FOLDER_SUGGESTIONS = 10
        private const val MAX_OPEN_FILE_SUGGESTIONS = 15
    }

    private fun createMatcher(searchText: String): MinusculeMatcher {
        return LookupMatchers.createMatcher(searchText)
    }

    private suspend fun getOpenFileCandidates(searchText: String): List<FileSearchCandidate> {
        val matcher = createMatcher(searchText)
        return readAction {
            val projectFileIndex = project.service<ProjectFileIndex>()
            project.service<FileEditorManager>().openFiles
                .filter { file ->
                    matchingDegree(file, projectFileIndex, matcher) != Int.MIN_VALUE
                }
                .map { file ->
                    FileSearchCandidate(
                        file = file,
                        source = FileSearchSource.OPEN
                    )
                }
        }
    }

    private suspend fun getRecentFileCandidates(searchText: String): List<FileSearchCandidate> {
        val matcher = createMatcher(searchText)
        return readAction {
            val projectFileIndex = project.service<ProjectFileIndex>()
            EditorHistoryManager.getInstance(project).fileList
                .asReversed()
                .asSequence()
                .filter { file ->
                    matchingDegree(file, projectFileIndex, matcher) != Int.MIN_VALUE
                }
                .take(MAX_SEARCH_FILES)
                .map { file ->
                    FileSearchCandidate(
                        file = file,
                        source = FileSearchSource.RECENT
                    )
                }
                .toList()
        }
    }

    private fun buildDefaultCandidates(
        openFiles: List<FileSearchCandidate>,
        recentFiles: List<FileSearchCandidate>
    ): List<FileSearchCandidate> {
        val prioritizedOpenFiles = openFiles.take(MAX_OPEN_FILE_SUGGESTIONS)
        val openFilePaths = prioritizedOpenFiles.mapTo(mutableSetOf()) { it.file.path }
        val recentBackfill = recentFiles
            .filterNot { it.file.path in openFilePaths }
            .take(MAX_DEFAULT_FILE_SUGGESTIONS - prioritizedOpenFiles.size)

        return prioritizedOpenFiles + recentBackfill
    }

    private suspend fun getFolderSuggestions(searchText: String): List<FolderActionItem> {
        val matcher = createMatcher(searchText)
        val resultLimit = if (searchText.isEmpty()) {
            MAX_DEFAULT_FOLDER_SUGGESTIONS
        } else {
            MAX_SEARCH_FOLDERS
        }

        return readAction {
            val projectFileIndex = project.service<ProjectFileIndex>()
            getProjectFolders(projectFileIndex)
                .asSequence()
                .filter { folder -> isVisibleProjectFolder(folder, projectFileIndex) }
                .mapNotNull { folder ->
                    val matchingDegree = if (searchText.isEmpty()) {
                        Int.MAX_VALUE
                    } else {
                        maxOf(
                            matcher.matchingDegree(folder.name),
                            matcher.matchingDegree(folder.path)
                        )
                    }
                    if (matchingDegree == Int.MIN_VALUE) {
                        null
                    } else {
                        folder to matchingDegree
                    }
                }
                .sortedWith(
                    compareByDescending<Pair<VirtualFile, Int>> { it.second }
                        .thenBy { it.first.path }
                )
                .take(resultLimit)
                .map { FolderActionItem(project, it.first) }
                .toList()
        }
    }

    private fun containsTag(file: VirtualFile): Boolean {
        return tagManager.containsTag(file)
    }

    private fun isVisibleProjectFile(
        file: VirtualFile,
        projectFileIndex: ProjectFileIndex
    ): Boolean {
        return isVisibleProjectItem(file, projectFileIndex) &&
            !file.isDirectory
    }

    private fun isVisibleProjectFolder(
        file: VirtualFile,
        projectFileIndex: ProjectFileIndex
    ): Boolean {
        return isProjectFolderCandidate(file, projectFileIndex) &&
            settingsService.isVirtualFileVisible(file) &&
            !file.isHiddenFileOrInHiddenDirectory() &&
            !containsTag(file)
    }

    private fun isVisibleProjectItem(
        file: VirtualFile,
        projectFileIndex: ProjectFileIndex
    ): Boolean {
        return file.isValid &&
            projectFileIndex.isInContent(file) &&
            settingsService.isVirtualFileVisible(file) &&
            !file.isHiddenFileOrInHiddenDirectory() &&
            !containsTag(file)
    }

    private fun isProjectFolderCandidate(
        file: VirtualFile,
        projectFileIndex: ProjectFileIndex
    ): Boolean {
        return file.isValid &&
            file.isDirectory &&
            projectFileIndex.isInContent(file) &&
            !file.isHiddenFileOrInHiddenDirectory()
    }

    private fun matchingDegree(
        file: VirtualFile,
        projectFileIndex: ProjectFileIndex,
        matcher: MinusculeMatcher
    ): Int {
        if (!isVisibleProjectFile(file, projectFileIndex)) {
            return Int.MIN_VALUE
        }

        return maxOf(
            matcher.matchingDegree(file.name),
            matcher.matchingDegree(file.path)
        )
    }

    private fun Iterable<FileSearchCandidate>.toFileSuggestions(searchText: String): List<LookupActionItem> {
        val fileItems = map { candidate ->
            FileActionItem(project, candidate.file, candidate.source)
        }

        val includeOpenFiles = if (searchText.isEmpty()) {
            listOf(IncludeOpenFilesActionItem())
        } else {
            val matcher = createMatcher(searchText)
            val item = IncludeOpenFilesActionItem()
            if (matcher.matchingDegree(item.displayName) != Int.MIN_VALUE) listOf(item) else emptyList()
        }

        return includeOpenFiles + fileItems
    }
    @Synchronized
    private fun getProjectFolders(projectFileIndex: ProjectFileIndex): List<VirtualFile> {
        val structureModCount = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount
        val rootModCount = ProjectRootManager.getInstance(project).modificationCount
        if (cachedFolderStructureModCount == structureModCount &&
            cachedFolderRootModCount == rootModCount
        ) {
            return cachedFolders
        }

        val folders = mutableListOf<VirtualFile>()
        projectFileIndex.iterateContent { file ->
            if (isProjectFolderCandidate(file, projectFileIndex)) {
                folders += file
            }
            true
        }
        cachedFolders = folders
        cachedFolderStructureModCount = structureModCount
        cachedFolderRootModCount = rootModCount
        return folders
    }
}
