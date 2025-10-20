package ee.carlrobert.codegpt.ui.textarea.header.tag

import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CopyOnWriteArraySet

class TagManager {

    private val tags = mutableSetOf<TagDetails>()
    private val listeners = CopyOnWriteArraySet<TagManagerListener>()

    fun addListener(listener: TagManagerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TagManagerListener) {
        listeners.remove(listener)
    }

    fun getTags(): Set<TagDetails> = synchronized(this) { tags.toSet() }

    fun containsTag(file: VirtualFile): Boolean = tags.any {
        // TODO: refactor
        if (it is SelectionTagDetails) {
            it.virtualFile == file
        } else if (it is FileTagDetails) {
            it.virtualFile == file
        } else if (it is EditorSelectionTagDetails) {
            it.virtualFile == file
        } else if (it is EditorTagDetails) {
            it.virtualFile == file
        } else {
            false
        }
    }

    fun addTag(tagDetails: TagDetails) {
        val wasAdded = synchronized(this) {
            if (tagDetails is EditorSelectionTagDetails) {
                remove(tagDetails)
            }

            if (tags.count { !it.selected } == 2) {
                remove(tags.sortedBy { it.createdOn }.first { !it.selected })
            }

            tags.add(tagDetails)
        }
        if (wasAdded) {
            listeners.forEach { it.onTagAdded(tagDetails) }
        }
    }

    fun updateSelectionTag(virtualFile: VirtualFile, selectionModel: SelectionModel) {
        val selectionTag = tags.firstOrNull {
            when (it) {
                is SelectionTagDetails -> it.virtualFile == virtualFile
                is EditorSelectionTagDetails -> it.virtualFile == virtualFile
                else -> false
            }
        } ?: return
        notifySelectionChanged(selectionTag, selectionModel)
    }

    fun notifySelectionChanged(tagDetails: TagDetails, selectionModel: SelectionModel) {
        val containsTag = synchronized(this) { tags.contains(tagDetails) }
        if (containsTag) {
            listeners.forEach { it.onTagSelectionChanged(tagDetails, selectionModel) }
        }
    }

    fun remove(tagDetails: TagDetails) {
        val wasRemoved = synchronized(this) { tags.remove(tagDetails) }
        if (wasRemoved) {
            listeners.forEach { it.onTagRemoved(tagDetails) }
        }
    }

    fun clear() {
        val removedTags = mutableListOf<TagDetails>()
        synchronized(this) {
            removedTags.addAll(tags)
            tags.clear()
        }
        removedTags.forEach { tag ->
            listeners.forEach { it.onTagRemoved(tag) }
        }
    }

    private fun isEditorTag(tagDetails: TagDetails): Boolean =
        tagDetails is EditorSelectionTagDetails || tagDetails is EditorTagDetails
}
