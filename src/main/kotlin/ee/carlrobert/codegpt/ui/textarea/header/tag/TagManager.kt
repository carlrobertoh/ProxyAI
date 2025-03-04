package ee.carlrobert.codegpt.ui.textarea.header.tag

import java.util.concurrent.CopyOnWriteArraySet

class TagManager {

    private val tags = mutableSetOf<TagDetails>()
    private val listeners = CopyOnWriteArraySet<TagManagerListener>()

    @Volatile
    private var enabled: Boolean = true

    fun addListener(listener: TagManagerListener) {
        listeners.add(listener)
    }

    fun enableEditorTags() {
        enabled = true
    }

    fun disableEditorTags() {
        enabled = false
        clear()
    }

    fun getTags(): Set<TagDetails> = synchronized(this) { tags.toSet() }

    fun addTag(tagDetails: TagDetails) {
        val wasAdded = synchronized(this) {
            if (!enabled && isEditorTag(tagDetails)) return

            if (tagDetails is EditorSelectionTagDetails) {
                tags.remove(tagDetails)
            }

            tags.add(tagDetails)
        }
        if (wasAdded) {
            listeners.forEach { it.onTagAdded(tagDetails) }
        }
    }

    fun notifySelectionChanged(tagDetails: TagDetails) {
        val containsTag = synchronized(this) { tags.contains(tagDetails) }
        if (containsTag) {
            listeners.forEach { it.onTagSelectionChanged(tagDetails) }
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
