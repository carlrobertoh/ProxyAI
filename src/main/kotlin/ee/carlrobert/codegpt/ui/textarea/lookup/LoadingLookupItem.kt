package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.ui.AnimatedIcon

class LoadingLookupItem(
    private val lookupString: String
) : AbstractLookupItem() {

    override val displayName = "Searching..."
    override val icon = AnimatedIcon.Default()
    override val enabled = false

    override fun setPresentation(
        element: LookupElement,
        presentation: LookupElementPresentation
    ) {
        presentation.itemText = displayName
        presentation.icon = icon
        presentation.isItemTextBold = false
    }

    override fun getLookupString(): String {
        return lookupString
    }
}
