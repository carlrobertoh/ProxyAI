package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.IncludeOpenFilesActionItem

class FilesGroupItem(
    private val project: Project,
    private val tagManager: TagManager
) : AbstractLookupGroupItem(), DynamicLookupGroupItem {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.files.displayName")
    override val icon = AllIcons.FileTypes.Any_type

    override suspend fun updateLookupList(lookup: LookupImpl, searchText: String) {
        ProjectFileIndex.getInstance(project).iterateContent {
            if (!it.isDirectory && !containsTag(it)) {
                runInEdt {
                    LookupUtil.addLookupItem(lookup, FileActionItem(project, it))
                }
            }
            true
        }
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        return readAction {
            val projectFileIndex = ProjectFileIndex.getInstance(project)
            FileEditorManager.getInstance(project).openFiles
                .filter { projectFileIndex.isInContent(it) && !containsTag(it) }
                .toFileSuggestions()
        }
    }

    private fun containsTag(file: VirtualFile): Boolean {
        return tagManager.containsTag(file)
    }

    private fun Iterable<VirtualFile>.toFileSuggestions(): List<LookupActionItem> {
        val selectedFileTags = TagUtil.getExistingTags(project, FileTagDetails::class.java)
        return filter { file -> selectedFileTags.none { it.virtualFile == file } }
            .take(10)
            .map { FileActionItem(project, it) } + listOf(IncludeOpenFilesActionItem())
    }
}