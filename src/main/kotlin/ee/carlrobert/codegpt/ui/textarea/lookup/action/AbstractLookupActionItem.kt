package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import ee.carlrobert.codegpt.ui.textarea.lookup.AbstractLookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem

abstract class AbstractLookupActionItem : AbstractLookupItem(), LookupActionItem {

    override fun setPresentation(element: LookupElement, presentation: LookupElementPresentation) {
        presentation.icon = icon
        presentation.itemText = displayName
        presentation.isItemTextBold = false
    }

    override fun getLookupString(): String {
        return "action_${displayName.replace(" ", "_").lowercase()}"
    }
}