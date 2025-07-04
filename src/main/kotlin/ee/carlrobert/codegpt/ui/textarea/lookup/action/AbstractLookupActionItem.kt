package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import ee.carlrobert.codegpt.ui.textarea.lookup.AbstractLookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import java.util.UUID

abstract class AbstractLookupActionItem : AbstractLookupItem(), LookupActionItem {

    private val id: UUID = UUID.randomUUID()

    override fun setPresentation(element: LookupElement, presentation: LookupElementPresentation) {
        presentation.icon = icon
        presentation.itemText = displayName
        presentation.isItemTextBold = false
    }

    override fun getLookupString(): String {
        return "action_${id}"
    }
}