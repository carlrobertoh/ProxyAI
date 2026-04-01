package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager
import ee.carlrobert.codegpt.ui.textarea.FileSearchCandidate
import ee.carlrobert.codegpt.ui.textarea.FileSearchProvider
import ee.carlrobert.codegpt.ui.textarea.FileSearchSource
import ee.carlrobert.codegpt.ui.textarea.NativeFileSearchProvider
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.IncludeOpenFilesActionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FilesGroupItem(
    private val project: Project,
    private val tagManager: TagManager,
    private val fileSearchProvider: FileSearchProvider = NativeFileSearchProvider(project)
) : AbstractLookupGroupItem(), DynamicLookupGroupItem {
    private val settingsService = project.service<ProxyAISettingsService>()

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.files.displayName")
    override val icon = AllIcons.FileTypes.Any_type

    override suspend fun updateLookupList(lookup: LookupImpl, searchText: String) {
        val lookupItems = getLookupItems(searchText)
            .filterIsInstance<FileActionItem>()

        withContext(Dispatchers.Default) {
            lookupItems.forEach { actionItem ->
                runInEdt {
                    LookupUtil.addLookupItem(lookup, actionItem, searchText = searchText)
                }
            }
        }
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        val normalizedSearchText = searchText.trim()
        val openFiles = getOpenFileCandidates(normalizedSearchText)
        val recentFiles = getRecentFileCandidates(normalizedSearchText, openFiles.isEmpty())
        val providerMatches = fileSearchProvider.search(normalizedSearchText, MAX_SEARCH_FILES)
        val providerFiles = readAction {
            val projectFileIndex = project.service<ProjectFileIndex>()
            providerMatches.filter { candidate ->
                isVisibleProjectFile(candidate.file, projectFileIndex)
            }
        }

        val orderedCandidates = buildList {
            if (normalizedSearchText.isEmpty()) {
                addAll(openFiles)
                addAll(recentFiles)
            } else {
                addAll(providerFiles)
                addAll(openFiles)
                addAll(recentFiles)
            }
        }.distinctBy { it.file.path }

        return orderedCandidates.toFileSuggestions()
    }

    companion object {
        private const val MAX_SEARCH_FILES = 200
    }

    private fun createMatcher(searchText: String): MinusculeMatcher {
        return if (searchText.isEmpty()) {
            NameUtil.buildMatcher("*").build()
        } else {
            NameUtil.buildMatcher("*$searchText").build()
        }
    }

    private suspend fun getOpenFileCandidates(searchText: String): List<FileSearchCandidate> {
        val matcher = createMatcher(searchText)
        return readAction {
            val projectFileIndex = project.service<ProjectFileIndex>()
            project.service<FileEditorManager>().openFiles
                .filter { file ->
                    isVisibleProjectFile(file, projectFileIndex) && matcher.matches(file.name)
                }
                .map { file ->
                    FileSearchCandidate(
                        file = file,
                        source = FileSearchSource.OPEN
                    )
                }
        }
    }

    private suspend fun getRecentFileCandidates(
        searchText: String,
        onlyWhenNoOpenFiles: Boolean
    ): List<FileSearchCandidate> {
        if (!onlyWhenNoOpenFiles) {
            return emptyList()
        }

        val matcher = createMatcher(searchText)
        return readAction {
            val projectFileIndex = project.service<ProjectFileIndex>()
            EditorHistoryManager.getInstance(project).fileList
                .asReversed()
                .asSequence()
                .filter { file ->
                    isVisibleProjectFile(file, projectFileIndex) && matcher.matches(file.name)
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

    private fun containsTag(file: VirtualFile): Boolean {
        return tagManager.containsTag(file)
    }

    private fun isVisibleProjectFile(
        file: VirtualFile,
        projectFileIndex: ProjectFileIndex
    ): Boolean {
        return !file.isDirectory &&
            projectFileIndex.isInContent(file) &&
            settingsService.isVirtualFileVisible(file) &&
            !containsTag(file)
    }

    private fun Iterable<FileSearchCandidate>.toFileSuggestions(): List<LookupActionItem> {
        val selectedFileTags = getExistingTags(project, FileTagDetails::class.java)
        val fileItems = filter { candidate ->
            selectedFileTags.none { it.virtualFile == candidate.file }
        }.map { candidate ->
            FileActionItem(project, candidate.file, candidate.source)
        }

        return listOf(IncludeOpenFilesActionItem()) + fileItems
    }

    fun <T : TagDetails> getExistingTags(
        project: Project,
        tagClass: Class<T>
    ): List<T> {
        return project.service<ChatToolWindowContentManager>()
            .tryFindActiveChatTabPanel()
            .map { it.selectedTags }
            .orElse(emptyList())
            .filterIsInstance(tagClass)
    }
}
