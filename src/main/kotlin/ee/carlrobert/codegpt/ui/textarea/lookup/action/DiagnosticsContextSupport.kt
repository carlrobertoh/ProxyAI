package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorSelectionTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.SelectionTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails

internal fun selectedContextFiles(tags: Collection<TagDetails>): List<VirtualFile> {
    return tags.asSequence()
        .filter { it.selected }
        .mapNotNull { tag ->
            when (tag) {
                is FileTagDetails -> tag.virtualFile
                is EditorTagDetails -> tag.virtualFile
                is SelectionTagDetails -> tag.virtualFile
                is EditorSelectionTagDetails -> tag.virtualFile
                else -> null
            }
        }
        .distinctBy { it.path }
        .toList()
}
