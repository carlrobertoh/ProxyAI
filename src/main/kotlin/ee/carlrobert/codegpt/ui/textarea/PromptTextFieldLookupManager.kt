package ee.carlrobert.codegpt.ui.textarea

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
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.FolderActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.WebActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.git.GitCommitActionItem

class PromptTextFieldLookupManager(
    private val project: Project,
    private val onLookupAdded: (LookupActionItem) -> Unit
) {

    fun createLookup(
        editor: Editor,
        lookupElements: Array<LookupElement>,
        searchText: String
    ): LookupImpl = runReadAction {
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
        onGroupSelected: (group: LookupGroupItem, searchText: String) -> Unit,
        onWebActionSelected: (WebActionItem) -> Unit,
        searchText: String = ""
    ): LookupImpl {
        val lookup = createLookup(editor, lookupElements, "")

        lookup.addLookupListener(object : LookupListener {
            override fun itemSelected(event: LookupEvent) {
                val suggestion = event.item?.getUserData(LookupItem.KEY) ?: return

                replaceAtSymbol(editor, suggestion)

                when (suggestion) {
                    is WebActionItem -> onWebActionSelected(suggestion)
                    is LookupGroupItem -> onGroupSelected(suggestion, searchText)
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
        val lookupElements = results.map { it.createLookupElement() }.toTypedArray()
        val lookup = createLookup(editor, lookupElements, "")

        lookup.addLookupListener(object : LookupListener {
            override fun itemSelected(event: LookupEvent) {
                val lookupString = event.item?.lookupString ?: return
                val suggestion =
                    event.item?.getUserData(LookupItem.KEY) as? LookupActionItem ?: return

                val offset = editor.caretModel.offset
                val start = offset - lookupString.length
                if (start >= 0) {
                    runUndoTransparentWriteAction {
                        editor.document.deleteString(start, offset)
                    }
                }

                replaceAtSymbolWithSearch(editor, suggestion, searchText)
                onLookupAdded(suggestion)
            }
        })

        lookup.refreshUi(false, true)
        lookup.showLookup()
        return lookup
    }

    fun showSuggestionLookup(
        editor: Editor,
        lookupElements: Array<LookupElement>,
        parentGroup: LookupGroupItem,
        onDynamicUpdate: (String) -> Unit,
        filterText: String = ""
    ): LookupImpl {
        val lookup = createLookup(editor, lookupElements, filterText)

        lookup.addLookupListener(object : LookupListener {
            override fun itemSelected(event: LookupEvent) {
                val lookupString = event.item?.lookupString ?: return
                val suggestion =
                    event.item?.getUserData(LookupItem.KEY) as? LookupActionItem ?: return

                val offset = editor.caretModel.offset
                val start = offset - lookupString.length
                if (start >= 0) {
                    runUndoTransparentWriteAction {
                        editor.document.deleteString(start, offset)
                    }
                }

                replaceAtSymbolWithSearch(editor, suggestion, filterText)
                onLookupAdded(suggestion)
            }
        })

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
        return if (atIndex >= 0 && atIndex < caretOffset) {
            text.substring(atIndex + 1, caretOffset)
        } else {
            ""
        }
    }

    private fun replaceAtSymbolWithSearch(
        editor: Editor,
        lookupItem: LookupItem,
        searchText: String
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
                || lookupItem is GitCommitActionItem
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