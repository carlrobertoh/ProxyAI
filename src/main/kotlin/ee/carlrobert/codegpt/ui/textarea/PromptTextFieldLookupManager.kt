package ee.carlrobert.codegpt.ui.textarea

import com.intellij.codeInsight.lookup.*
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.ui.textarea.lookup.*
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.action.CodeAnalyzeActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.FolderActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.WebActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem

class PromptTextFieldLookupManager(
    private val project: Project,
    private val onLookupAdded: (LookupActionItem) -> Unit,
) {

    companion object {
        const val EMPTY_RESULTS_TEXT = "No results"
    }

    fun createLookup(
        editor: Editor,
        lookupElements: Array<LookupElement>,
        searchText: String,
    ): LookupImpl = runReadActionBlocking {
        LookupManager.getInstance(project).createLookup(
            editor,
            lookupElements,
            searchText,
            LookupArranger.DefaultArranger()
        ) as LookupImpl
    }

    fun showGroupLookup(
        editor: Editor,
        lookupElements: Array<LookupElement>,
        onGroupSelected: (group: LookupGroupItem) -> Unit,
        onWebActionSelected: (WebActionItem) -> Unit,
        onCodeAnalyzeSelected: (CodeAnalyzeActionItem) -> Unit,
    ): LookupImpl {
        val lookup = createLookup(editor, lookupElements, "")
        lookup.addLookupListener(object : LookupListener {
            private var pendingCleanup: LookupSelectionCleanup? = null

            override fun beforeItemSelected(event: LookupEvent): Boolean {
                pendingCleanup = createLookupSelectionCleanup(editor, event)
                return true
            }

            override fun itemSelected(event: LookupEvent) {
                val suggestion = event.item?.getUserData(LookupItem.KEY) ?: return
                val cleanup = pendingCleanup ?: createLookupSelectionCleanup(editor, event)

                when (suggestion) {
                    is WebActionItem -> {
                        removeLookupText(editor, cleanup, LookupCleanupMode.REMOVE_TOKEN)
                        onWebActionSelected(suggestion)
                        removeLookupTextLater(editor, cleanup, LookupCleanupMode.REMOVE_TOKEN)
                    }

                    is CodeAnalyzeActionItem -> {
                        removeLookupText(editor, cleanup, LookupCleanupMode.REMOVE_TOKEN)
                        onCodeAnalyzeSelected(suggestion)
                        removeLookupTextLater(editor, cleanup, LookupCleanupMode.REMOVE_TOKEN)
                    }

                    is LookupGroupItem -> {
                        removeLookupText(editor, cleanup, LookupCleanupMode.KEEP_AT_SYMBOL)
                        onGroupSelected(suggestion) // suppress stays active until suggestion lookup opens
                        removeLookupTextLater(editor, cleanup, LookupCleanupMode.KEEP_AT_SYMBOL)
                    }

                    is LookupActionItem -> {
                        removeLookupText(editor, cleanup, LookupCleanupMode.REMOVE_TOKEN)
                        onLookupAdded(suggestion)
                        removeLookupTextLater(editor, cleanup, LookupCleanupMode.REMOVE_TOKEN)
                    }
                }
            }
        })
        lookup.refreshUi(false, true)
        lookup.showLookup()
        return lookup
    }

    fun showSearchResultsLookup(
        editor: Editor,
        results: List<LookupItem>,
        searchText: String,
        isCalculating: Boolean = false,
        searchTextProvider: (() -> String)? = null,
        lookupPrefix: String = searchText
    ): LookupImpl {
        val lookup = createLookup(editor, emptyArray(), lookupPrefix)
        updateSearchResultsLookup(
            lookup,
            results,
            searchText,
            isCalculating,
            searchTextProvider,
            matcherPrefix = lookupPrefix
        )
        addFinalActionSelectionListener(lookup, editor)
        lookup.showLookup()
        return lookup
    }

    fun updateSearchResultsLookup(
        lookup: LookupImpl,
        results: List<LookupItem>,
        searchText: String,
        isCalculating: Boolean = false,
        searchTextProvider: (() -> String)? = null,
        matcherPrefix: String = searchText
    ): Int {
        return replaceLookupItems(
            lookup,
            results,
            searchText,
            isCalculating,
            searchTextProvider,
            matcherPrefix
        )
    }

    fun updateSuggestionLookup(
        lookup: LookupImpl,
        results: List<LookupItem>,
        searchText: String,
        isCalculating: Boolean = false,
        searchTextProvider: (() -> String)? = null,
        matcherPrefix: String = searchText
    ): Int {
        return replaceLookupItems(
            lookup,
            results,
            searchText,
            isCalculating,
            searchTextProvider,
            matcherPrefix
        )
    }

    fun getSelectedLookupItemKey(lookup: LookupImpl): String? {
        val currentItem = lookup.currentItem ?: return null
        val lookupItem = currentItem.getUserData(LookupItem.KEY) ?: return null
        return resultKey(lookupItem)
    }

    fun restoreSelectedLookupItem(
        lookup: LookupImpl,
        selectedKey: String?
    ) {
        if (selectedKey == null) {
            return
        }

        val matchingItem = lookup.items.firstOrNull { element ->
            val lookupItem = element.getUserData(LookupItem.KEY) ?: return@firstOrNull false
            resultKey(lookupItem) == selectedKey
        } ?: return

        lookup.currentItem = matchingItem
        lookup.ensureSelectionVisible(false)
    }

    fun showSuggestionLookup(
        editor: Editor,
        lookupItems: List<LookupItem>,
        searchText: String = "",
        isCalculating: Boolean = false,
        searchTextProvider: (() -> String)? = null,
        lookupPrefix: String = searchText,
    ): LookupImpl {
        val lookup = createLookup(editor, emptyArray(), lookupPrefix)
        updateSuggestionLookup(
            lookup,
            lookupItems,
            searchText,
            isCalculating,
            searchTextProvider,
            matcherPrefix = lookupPrefix
        )
        addFinalActionSelectionListener(lookup, editor)
        lookup.showLookup()
        return lookup
    }

    private fun addFinalActionSelectionListener(
        lookup: LookupImpl,
        editor: Editor
    ) {
        lookup.addLookupListener(object : LookupListener {
            private var pendingCleanup: LookupSelectionCleanup? = null

            override fun beforeItemSelected(event: LookupEvent): Boolean {
                pendingCleanup = createLookupSelectionCleanup(editor, event)
                return true
            }

            override fun itemSelected(event: LookupEvent) {
                val suggestion =
                    event.item?.getUserData(LookupItem.KEY) as? LookupActionItem ?: return
                val cleanup = pendingCleanup ?: createLookupSelectionCleanup(editor, event)
                removeLookupText(editor, cleanup, LookupCleanupMode.REMOVE_TOKEN)
                onLookupAdded(suggestion)
                removeLookupTextLater(editor, cleanup, LookupCleanupMode.REMOVE_TOKEN)
            }
        })
    }

    private fun createLookupSelectionCleanup(
        editor: Editor,
        event: LookupEvent
    ): LookupSelectionCleanup {
        val token = AtLookupToken.from(editor)
        val item = event.item
        val lookupItem = item?.getUserData(LookupItem.KEY)
        val lookupStrings = buildSet {
            item?.lookupString?.let(::add)
            item?.allLookupStrings?.let(::addAll)
            lookupItem?.displayName?.let(::add)
        }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }

        return LookupSelectionCleanup(token?.startOffset, lookupStrings)
    }

    private fun removeLookupTextLater(
        editor: Editor,
        cleanup: LookupSelectionCleanup?,
        mode: LookupCleanupMode
    ) {
        ApplicationManager.getApplication().invokeLater {
            removeLookupText(editor, cleanup, mode)
        }
    }

    private fun removeLookupText(
        editor: Editor,
        cleanup: LookupSelectionCleanup?,
        mode: LookupCleanupMode
    ) {
        runUndoTransparentWriteAction {
            val token = AtLookupToken.from(editor)
            val anchorOffset = token?.startOffset ?: cleanup?.startOffset ?: editor.caretModel.offset
            if (token != null) {
                when (mode) {
                    LookupCleanupMode.REMOVE_TOKEN -> {
                        editor.document.deleteString(token.startOffset, token.endOffset)
                        editor.caretModel.moveToOffset(token.startOffset)
                    }

                    LookupCleanupMode.KEEP_AT_SYMBOL -> {
                        editor.document.deleteString(token.startOffset + 1, token.endOffset)
                        editor.caretModel.moveToOffset(token.startOffset + 1)
                    }
                }
            }

            val labelOffset = when (mode) {
                LookupCleanupMode.REMOVE_TOKEN -> anchorOffset
                LookupCleanupMode.KEEP_AT_SYMBOL -> (anchorOffset + 1).coerceAtMost(editor.document.textLength)
            }
            cleanup?.lookupStrings.orEmpty().firstOrNull {
                removeLookupStringAt(editor, labelOffset, it)
            } ?: removeLookupStringBeforeCaret(editor, cleanup?.lookupStrings.orEmpty())
        }
    }

    private fun removeLookupStringAt(
        editor: Editor,
        offset: Int,
        lookupString: String
    ): Boolean {
        val document = editor.document
        if (offset < 0 || offset + lookupString.length > document.textLength) {
            return false
        }

        if (document.charsSequence.substring(offset, offset + lookupString.length) != lookupString) {
            return false
        }

        document.deleteString(offset, offset + lookupString.length)
        editor.caretModel.moveToOffset(offset)
        return true
    }

    private fun removeLookupStringBeforeCaret(
        editor: Editor,
        lookupStrings: List<String>
    ) {
        val caretOffset = editor.caretModel.offset
        lookupStrings.firstOrNull { lookupString ->
            val startOffset = caretOffset - lookupString.length
            removeLookupStringAt(editor, startOffset, lookupString)
        }
    }

    fun replaceLookupItems(
        lookup: LookupImpl,
        results: List<LookupItem>,
        searchText: String,
        isCalculating: Boolean,
        searchTextProvider: (() -> String)?,
        matcherPrefix: String
    ): Int {
        if (lookup.isLookupDisposed) {
            return 0
        }

        val selectedKey = getSelectedLookupItemKey(lookup)
        lookup.arranger = LookupArranger.DefaultArranger()
        LookupUtil.addLookupItems(
            lookup,
            results.mapIndexed { index, result ->
                result to resultPriority(result, index, results.size)
            },
            searchText,
            searchTextProvider,
            matcherPrefix
        )
        configureLookup(lookup, isCalculating)
        restoreSelectedLookupItem(lookup, selectedKey)
        return results.size
    }

    private fun configureLookup(
        lookup: LookupImpl,
        isCalculating: Boolean
    ) {
        lookup.isStartCompletionWhenNothingMatches = true
        lookup.dummyItemCount = 0
        lookup.isCalculating = isCalculating
        lookup.refreshUi(false, true)
    }

    private fun resultPriority(
        result: LookupItem,
        index: Int,
        total: Int
    ): Double {
        return if (result is StatusLookupItem || result is LoadingLookupItem) {
            0.0
        } else {
            (total - index).toDouble()
        }
    }

    private fun resultKey(result: LookupItem): String {
        return when (result) {
            is FileActionItem -> "file:${result.file.path}"
            is FolderActionItem -> "folder:${result.folder.path}"
            else -> "${result::class.qualifiedName}:${result.displayName}"
        }
    }

    private data class LookupSelectionCleanup(
        val startOffset: Int?,
        val lookupStrings: List<String>
    )

    private enum class LookupCleanupMode {
        REMOVE_TOKEN,
        KEEP_AT_SYMBOL
    }
}
