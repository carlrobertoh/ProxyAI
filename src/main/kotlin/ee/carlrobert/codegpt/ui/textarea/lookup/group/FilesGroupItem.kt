package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.NameUtil
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.IncludeOpenFilesActionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FilesGroupItem(
    private val project: Project,
    private val tagManager: TagManager
) : AbstractLookupGroupItem(), DynamicLookupGroupItem {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.files.displayName")
    override val icon = AllIcons.FileTypes.Any_type

    override suspend fun updateLookupList(lookup: LookupImpl, searchText: String) {
        withContext(Dispatchers.Default) {
            project.service<ProjectFileIndex>().iterateContent {
                if (!it.isDirectory && !containsTag(it)) {
                    val actionItem = FileActionItem(project, it)
                    runInEdt {
                        LookupUtil.addLookupItem(lookup, actionItem)
                    }
                }
                true
            }
        }
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        return readAction {
            val projectFileIndex = project.service<ProjectFileIndex>()
            val matcher = NameUtil.buildMatcher("*$searchText").build()
            val matchingFiles = mutableListOf<VirtualFile>()

            projectFileIndex.iterateContent { file ->
                if (!file.isDirectory &&
                    !containsTag(file) &&
                    (searchText.isEmpty() || matcher.matchingDegree(file.name) != Int.MIN_VALUE)
                ) {
                    matchingFiles.add(file)
                }
                true
            }

            val openFiles = project.service<FileEditorManager>().openFiles
                .filter {
                    projectFileIndex.isInContent(it) &&
                            !containsTag(it) &&
                            (searchText.isEmpty() || matcher.matchingDegree(it.name) != Int.MIN_VALUE)
                }

            (matchingFiles + openFiles).distinctBy { it.path }.toFileSuggestions()
        }
    }

    private fun containsTag(file: VirtualFile): Boolean {
        return tagManager.containsTag(file)
    }

    private fun Iterable<VirtualFile>.toFileSuggestions(): List<LookupActionItem> {
        val selectedFileTags = TagUtil.getExistingTags(project, FileTagDetails::class.java)
        return filter { file -> selectedFileTags.none { it.virtualFile == file } }
            .map { FileActionItem(project, it) } + listOf(IncludeOpenFilesActionItem())
    }
}