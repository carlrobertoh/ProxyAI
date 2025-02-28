package ee.carlrobert.codegpt.ui.textarea.header.tag

import java.util.concurrent.CopyOnWriteArraySet

class TagManager {

    private val tags = CopyOnWriteArraySet<TagDetails>()
    private val listeners = CopyOnWriteArraySet<TagManagerListener>()

    fun addListener(listener: TagManagerListener) {
        listeners.add(listener)
    }

    fun getTags(): Set<TagDetails> = tags.toSet()

    fun addTag(tagDetails: TagDetails) {
        if (tagDetails is EditorSelectionTagDetails && tags.contains(tagDetails)) {
            tags.remove(tagDetails)
        }
        if (tags.add(tagDetails)) {
            listeners.forEach { it.onTagAdded(tagDetails) }
        }
    }

    fun notifySelectionChanged(tagDetails: TagDetails) {
        if (tags.contains(tagDetails)) {
            listeners.forEach { it.onTagSelectionChanged(tagDetails) }
        }
    }

    fun remove(tagDetails: TagDetails) {
        if (tags.remove(tagDetails)) {
            listeners.forEach { it.onTagRemoved(tagDetails) }
        }
    }

    fun clear() {
        val tagsToRemove = tags.toList()
        tags.clear()
        tagsToRemove.forEach { tag ->
            listeners.forEach { it.onTagRemoved(tag) }
        }
    }
}
