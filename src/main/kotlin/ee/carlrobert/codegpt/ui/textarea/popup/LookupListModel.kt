package ee.carlrobert.codegpt.ui.textarea.popup

import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItem
import javax.swing.AbstractListModel

class LookupListModel(private val items: List<LookupItem>) : AbstractListModel<LookupItem>() {
    
    override fun getSize(): Int = items.size
    
    override fun getElementAt(index: Int): LookupItem = items[index]
} 