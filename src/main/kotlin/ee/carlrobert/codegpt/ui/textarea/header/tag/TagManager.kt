package ee.carlrobert.codegpt.ui.textarea.header.tag

import com.intellij.openapi.application.runInEdt
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

            if (tagDetails is EditorTagDetails) {
                val unselectedEditorTags = tags
                    .filterIsInstance<EditorTagDetails>()
                    .filterNot { it.selected }
                    .sortedBy { it.createdOn }
                if (unselectedEditorTags.size == 2) {
                    remove(unselectedEditorTags.first())
                }
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

    fun notifyTagUpdated(tagDetails: TagDetails) {
        val containsTag = synchronized(this) { tags.contains(tagDetails) }
        if (containsTag) {
            listeners.forEach { it.onTagUpdated(tagDetails) }
        }
    }

    fun updateTag(oldTag: TagDetails, newTag: TagDetails) {
        runInEdt {
            performTagUpdate(oldTag, newTag)
        }
    }

    private fun performTagUpdate(oldTag: TagDetails, newTag: TagDetails): Boolean {
        synchronized(this) {
            if (tags.remove(oldTag)) {
                tags.add(newTag)

                if (newTag is McpTagDetails) {
                    var updatedInPlace = false
                    listeners.forEach { listener ->
                        if (listener is McpTagUpdateListener) {
                            if (listener.updateMcpTagInPlace(newTag)) {
                                updatedInPlace = true
                            }
                        }
                    }

                    if (updatedInPlace) {
                        return true
                    } else {
                        listeners.forEach { it.onTagRemoved(oldTag) }
                        listeners.forEach { it.onTagAdded(newTag) }
                        return true
                    }
                } else {
                    listeners.forEach { it.onTagRemoved(oldTag) }
                    listeners.forEach { it.onTagAdded(newTag) }
                    return true
                }
            } else {
                return false
            }
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
}

interface McpTagUpdateListener {
    fun updateMcpTagInPlace(tagDetails: McpTagDetails): Boolean
}
