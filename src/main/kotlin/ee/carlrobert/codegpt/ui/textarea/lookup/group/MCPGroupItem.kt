package ee.carlrobert.codegpt.ui.textarea.lookup.group

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import javax.swing.Icon

class MCPGroupItem : AbstractLookupGroupItem() {

    override val displayName: String = CodeGPTBundle.get("suggestionGroupItem.mcp.displayName")
    override val icon: Icon = Icons.MCP

    override fun setPresentation(element: LookupElement, presentation: LookupElementPresentation) {
        super.setPresentation(element, presentation)

        presentation.icon = IconLoader.getDisabledIcon(icon)
        presentation.isTypeGrayed = true
        presentation.setTypeText("", IconLoader.getDisabledIcon(AllIcons.Icons.Ide.NextStep))
        presentation.itemTextForeground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    override suspend fun getLookupItems(searchText: String): List<LookupActionItem> {
        return emptyList()
    }
}