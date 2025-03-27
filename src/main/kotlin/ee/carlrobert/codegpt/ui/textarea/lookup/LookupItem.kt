package ee.carlrobert.codegpt.ui.textarea.lookup

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import javax.swing.Icon

interface LookupItem {
    companion object {
        val KEY: Key<LookupItem> = Key.create("SUGGESTION_ITEM_KEY")
    }

    val displayName: String
    val icon: Icon?
    val enabled: Boolean
        get() = true

    fun createLookupElement(): LookupElement
    fun setPresentation(element: LookupElement, presentation: LookupElementPresentation)
}

interface LookupGroupItem : LookupItem {
    suspend fun getLookupItems(searchText: String = ""): List<LookupItem>
}

interface DynamicLookupGroupItem : LookupGroupItem {
    suspend fun updateLookupList(lookup: LookupImpl, searchText: String)
}

interface LookupActionItem : LookupItem {
    fun execute(project: Project, userInputPanel: UserInputPanel)
}