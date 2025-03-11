package ee.carlrobert.codegpt.ui.textarea

import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorSelectionTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails

internal class TagDetailsComparator : Comparator<TagDetails> {
    override fun compare(o1: TagDetails, o2: TagDetails): Int {
        val priority1 = getPriority(o1)
        val priority2 = getPriority(o2)

        return priority1.compareTo(priority2)
    }

    private fun getPriority(tag: TagDetails): Int {
        return when (tag) {
            is EditorSelectionTagDetails -> 0
            is EditorTagDetails -> 1
            else -> 2
        }
    }
}