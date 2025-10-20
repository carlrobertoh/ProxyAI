package ee.carlrobert.codegpt.ui.textarea.header.tag

import com.intellij.openapi.editor.SelectionModel

interface TagManagerListener {
    fun onTagAdded(tag: TagDetails)
    fun onTagRemoved(tag: TagDetails)
    fun onTagSelectionChanged(tag: TagDetails, selectionModel: SelectionModel)
}