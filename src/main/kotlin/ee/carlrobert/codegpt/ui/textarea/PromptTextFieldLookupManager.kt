package ee.carlrobert.codegpt.ui.textarea

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.*
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.codeInsight.lookup.impl.PrefixChangeListener
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import ee.carlrobert.codegpt.ui.textarea.lookup.*
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.action.CodeAnalyzeActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.FolderActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.InsertsDisplayNameLookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.WebActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem

class PromptTextFieldLookupManager(
    private val project: Project,
    private val onLookupAdded: (LookupActionItem) -> Unit
) {

    fun createLookup(
        editor: Editor,
        lookupElements: Array<LookupElement>,
        searchText: String
    ): LookupImpl = runReadAction {
        val lookup = LookupManager.getInstance(project).createLookup(
            editor,
            lookupElements,
            searchText,
            LookupArranger.DefaultArranger()
        ) as LookupImpl

        lookup.addLookupListener(object : LookupListener {
            override fun itemSelected(event: LookupEvent) {
                val suggestion =
                    event.item?.getUserData(LookupItem.KEY) as? LookupActionItem ?: return

                replaceAtSymbolWithSearch(editor, suggestion)
                onLookupAdded(suggestion)
            }
        })

        lookup
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
            override fun itemSelected(event: LookupEvent) {
                val suggestion = event.item?.getUserData(LookupItem.KEY) ?: return

                replaceAtSymbol(editor, suggestion)

                when (suggestion) {
                    is WebActionItem -> onWebActionSelected(suggestion)
                    is CodeAnalyzeActionItem -> onCodeAnalyzeSelected(suggestion)
                    is LookupGroupItem -> onGroupSelected(suggestion)
                    is LookupActionItem -> onLookupAdded(suggestion)
                }
            }
        })

        lookup.refreshUi(false, true)
        lookup.showLookup()
        return lookup
    }

    fun showSearchResultsLookup(
        editor: Editor,
        results: List<LookupActionItem>,
        searchText: String
    ): LookupImpl {
        val lookupElements = results.toPrioritizedLookupElements(searchText)
        val lookup = createLookup(editor, lookupElements, "")
        lookup.refreshUi(false, true)
        lookup.showLookup()
        return lookup
    }

    fun updateSearchResultsLookup(
        lookup: LookupImpl,
        results: List<LookupActionItem>
    ): Int {
        val existingKeys = lookup.items.mapNotNull { element ->
            (element.getUserData(LookupItem.KEY) as? LookupActionItem)?.let(::resultKey)
        }.toMutableSet()
        val newResults = results.filter { result -> existingKeys.add(resultKey(result)) }
        if (newResults.isEmpty()) {
            lookup.refreshUi(false, true)
            return 0
        }

        LookupUtil.addLookupItems(
            lookup,
            newResults.mapIndexed { index, result ->
                result to resultPriority(result, index, newResults.size)
            },
            getSearchTextFromLookup(lookup)
        )
        return newResults.size
    }

    fun showSuggestionLookup(
        editor: Editor,
        lookupElements: Array<LookupElement>,
        parentGroup: LookupGroupItem,
        onDynamicUpdate: (String) -> Unit
    ): LookupImpl {
        val lookup = createLookup(editor, lookupElements, "")
        if (parentGroup is DynamicLookupGroupItem) {
            setupDynamicLookupListener(lookup, onDynamicUpdate)
        }

        lookup.refreshUi(false, true)
        lookup.showLookup()
        return lookup
    }

    private fun setupDynamicLookupListener(
        lookup: LookupImpl,
        onDynamicUpdate: (String) -> Unit
    ) {
        lookup.addPrefixChangeListener(object : PrefixChangeListener {
            override fun afterAppend(c: Char) {
                val searchText = getSearchTextFromLookup(lookup)
                if (searchText.length >= PromptTextFieldConstants.MIN_DYNAMIC_SEARCH_LENGTH) {
                    onDynamicUpdate(searchText)
                }
            }

            override fun afterTruncate() {
                val searchText = getSearchTextFromLookup(lookup)
                if (searchText.isEmpty()) {
                    onDynamicUpdate("")
                }
            }
        }, lookup)
    }

    private fun getSearchTextFromLookup(lookup: LookupImpl): String {
        val editor = lookup.editor
        val text = editor.document.text
        val atIndex = text.lastIndexOf(PromptTextFieldConstants.AT_SYMBOL)
        return if (atIndex >= 0) text.substring(atIndex + 1) else ""
    }

    private fun getSearchTextFromEditor(editor: Editor): String {
        val text = editor.document.text
        val caretOffset = editor.caretModel.offset
        val atIndex = text.lastIndexOf(PromptTextFieldConstants.AT_SYMBOL)
        return if (atIndex in 0..<caretOffset) {
            text.substring(atIndex + 1, caretOffset)
        } else {
            ""
        }
    }

    private fun replaceAtSymbolWithSearch(
        editor: Editor,
        lookupItem: LookupItem
    ) {
        val atPos = findAtSymbolPosition(editor)
        if (atPos >= 0) {
            runUndoTransparentWriteAction {
                val actualSearchText = getSearchTextFromEditor(editor)
                val endPos = atPos + 1 + actualSearchText.length
                editor.document.deleteString(atPos, endPos)

                if (shouldInsertDisplayName(lookupItem)) {
                    insertWithHighlight(editor, atPos, lookupItem.displayName)
                }
            }
        }
    }

    private fun replaceAtSymbol(editor: Editor, lookupItem: LookupItem) {
        val offset = editor.caretModel.offset
        val start = findAtSymbolPosition(editor)
        if (start >= 0) {
            runUndoTransparentWriteAction {
                val shouldInsert = shouldInsertDisplayName(lookupItem)
                if (shouldInsert) {
                    editor.document.deleteString(start, offset)
                    insertWithHighlight(editor, start, lookupItem.displayName)
                } else {
                    editor.document.deleteString(start + 1, offset)
                }
            }
        }
    }

    private fun shouldInsertDisplayName(lookupItem: LookupItem): Boolean {
        return lookupItem is FileActionItem
                || lookupItem is FolderActionItem
                || lookupItem is InsertsDisplayNameLookupItem
    }

    private fun List<LookupActionItem>.toPrioritizedLookupElements(
        searchText: String
    ): Array<LookupElement> {
        return mapIndexed { index, result ->
            PrioritizedLookupElement.withPriority(
                result.createLookupElement(searchText),
                resultPriority(result, index, size)
            )
        }.toTypedArray()
    }

    private fun resultPriority(
        result: LookupActionItem,
        index: Int,
        total: Int
    ): Double {
        val sourcePriority = when (result) {
            is FileActionItem -> when (result.source) {
                FileSearchSource.NATIVE -> 3_000.0
                FileSearchSource.OPEN -> 2_500.0
                FileSearchSource.RECENT -> 2_000.0
            }

            else -> 1_000.0
        }
        return sourcePriority - index.toDouble() / maxOf(total, 1)
    }

    private fun resultKey(result: LookupActionItem): String {
        return when (result) {
            is FileActionItem -> "file:${result.file.path}"
            is FolderActionItem -> "folder:${result.folder.path}"
            else -> "${result::class.qualifiedName}:${result.displayName}"
        }
    }

    private fun insertWithHighlight(editor: Editor, position: Int, text: String) {
        editor.document.insertString(position, text)
        editor.caretModel.moveToOffset(position + text.length)
        editor.markupModel.addRangeHighlighter(
            position,
            position + text.length,
            HighlighterLayer.SELECTION,
            TextAttributes().apply {
                foregroundColor = JBColor(
                    PromptTextFieldConstants.LIGHT_THEME_COLOR,
                    PromptTextFieldConstants.DARK_THEME_COLOR
                )
            },
            HighlighterTargetArea.EXACT_RANGE
        )
    }

    private fun findAtSymbolPosition(editor: Editor): Int {
        val atPos = editor.document.text.lastIndexOf(PromptTextFieldConstants.AT_SYMBOL)
        return if (atPos >= 0) atPos else -1
    }
}
