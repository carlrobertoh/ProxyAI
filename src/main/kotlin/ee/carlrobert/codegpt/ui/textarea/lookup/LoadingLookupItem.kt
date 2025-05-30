package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import ee.carlrobert.codegpt.CodeGPTBundle
import javax.swing.Icon

class LoadingLookupItem : AbstractLookupItem() {
    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.loading.displayName")
    override val icon: Icon = AllIcons.Process.Step_1

    override fun setPresentation(element: LookupElement, presentation: LookupElementPresentation) {
        presentation.icon = icon
        presentation.itemText = displayName
        presentation.isItemTextBold = false
        presentation.isItemTextItalic = true
        presentation.itemTextForeground = com.intellij.ui.JBColor.GRAY
    }

    override fun getLookupString(): String {
        return "loading_search"
    }
} 