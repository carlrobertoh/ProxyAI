package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation

class StatusLookupItem(
    override val displayName: String,
    private val lookupString: String = displayName
) : AbstractLookupItem() {

    override val icon = null
    override val enabled = false

    override fun setPresentation(
        element: LookupElement,
        presentation: LookupElementPresentation
    ) {
        presentation.itemText = displayName
        presentation.isItemTextBold = false
    }

    override fun getLookupString(): String {
        return lookupString
    }
}
