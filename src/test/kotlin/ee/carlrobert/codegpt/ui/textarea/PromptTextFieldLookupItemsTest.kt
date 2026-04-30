package ee.carlrobert.codegpt.ui.textarea

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.ui.textarea.lookup.AbstractLookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItemKeepOpenPrefixMatcher
import ee.carlrobert.codegpt.ui.textarea.lookup.LoadingLookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.StatusLookupItem
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class PromptTextFieldLookupItemsTest {

    @Test
    fun `calculating empty results returns loading row`() {
        val items = PromptTextField.toSearchLookupItems(
            results = emptyList(),
            searchText = "abc",
            isCalculating = true
        )

        assertThat(items).hasSize(1)
        assertThat(items.single()).isInstanceOf(LoadingLookupItem::class.java)
    }

    @Test
    fun `done empty results returns no results row`() {
        val items = PromptTextField.toSearchLookupItems(
            results = emptyList(),
            searchText = "abc",
            isCalculating = false
        )

        assertThat(items).hasSize(1)
        assertThat(items.single()).isInstanceOf(StatusLookupItem::class.java)
        assertThat(items.single().displayName)
            .isEqualTo(PromptTextFieldLookupManager.EMPTY_RESULTS_TEXT)
    }

    @Test
    fun `pending empty results before loading reveal returns no rows`() {
        val items = PromptTextField.toSearchLookupItems(
            results = emptyList(),
            searchText = "abc",
            isCalculating = false,
            showEmptyStatus = false
        )

        assertThat(items).isEmpty()
    }

    @Test
    fun `calculating real results appends loading row`() {
        val result = TestLookupActionItem("abc.kt")

        val items = PromptTextField.toSearchLookupItems(
            results = listOf(result),
            searchText = "abc",
            isCalculating = true
        )

        assertThat(items).containsExactly(result, items.last())
        assertThat(items.last()).isInstanceOf(LoadingLookupItem::class.java)
    }

    @Test
    fun `calculating empty results keeps previous visible rows with loading row`() {
        val previous = TestLookupActionItem("abc.kt")

        val items = PromptTextField.toSearchLookupItems(
            results = emptyList(),
            searchText = "abcd",
            isCalculating = true,
            previousVisibleLookupItems = listOf(previous)
        )

        assertThat(items).containsExactly(previous, items.last())
        assertThat(items.last()).isInstanceOf(LoadingLookupItem::class.java)
    }

    @Test
    fun `keep open matcher exposes matching fragments for lookup highlighting`() {
        val matcher = LookupItemKeepOpenPrefixMatcher("@files abc", "abc", null)

        val fragments = matcher.getMatchingFragments("abc", "my-abc-file.kt")

        assertThat(fragments).isNotNull
        assertThat(fragments!!.map { it.startOffset to it.endOffset })
            .contains(3 to 6)
    }

    @Test
    fun `keep open matcher uses latest provided search text for lookup highlighting`() {
        var searchText = "abc"
        val matcher = LookupItemKeepOpenPrefixMatcher("@files abc", "ignored") { searchText }

        searchText = "file"
        val fragments = matcher.getMatchingFragments("@files abc", "my-abc-file.kt")

        assertThat(fragments).isNotNull
        assertThat(fragments!!.map { it.startOffset to it.endOffset })
            .contains(7 to 11)
    }

    private class TestLookupActionItem(
        override val displayName: String
    ) : AbstractLookupItem(), LookupActionItem {

        override val icon = null

        override fun execute(project: Project, userInputPanel: UserInputPanel) = Unit

        override fun setPresentation(
            element: LookupElement,
            presentation: LookupElementPresentation
        ) {
            presentation.itemText = displayName
        }

        override fun getLookupString(): String {
            return displayName
        }
    }
}
