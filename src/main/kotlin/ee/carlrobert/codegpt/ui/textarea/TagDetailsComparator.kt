package ee.carlrobert.codegpt.ui.textarea

import ee.carlrobert.codegpt.ui.textarea.header.tag.*

internal class TagDetailsComparator : Comparator<TagDetails> {
    override fun compare(o1: TagDetails, o2: TagDetails): Int {
        return getPriority(o1).compareTo(getPriority(o2))
    }

    private fun getPriority(tag: TagDetails): Int {
        if (!tag.selected && tag !is CodeAnalyzeTagDetails) {
            return Int.MAX_VALUE
        }

        return when (tag) {
            is CodeAnalyzeTagDetails,
            is EditorSelectionTagDetails -> 0

            is SelectionTagDetails -> 5
            is DocumentationTagDetails,
            is PersonaTagDetails,
            is GitCommitTagDetails,
            is CurrentGitChangesTagDetails,
            is FolderTagDetails,
            is WebTagDetails -> 10

            is EditorTagDetails,
            is FileTagDetails -> 15

            else -> 20
        }
    }
}
