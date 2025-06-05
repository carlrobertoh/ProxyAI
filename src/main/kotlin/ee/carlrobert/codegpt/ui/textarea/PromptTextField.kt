package ee.carlrobert.codegpt.ui.textarea

import com.intellij.codeInsight.lookup.*
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.codeInsight.lookup.impl.PrefixChangeListener
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTKeys.IS_PROMPT_TEXT_FIELD_DOCUMENT
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.FolderActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.WebActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.git.GitCommitActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.group.*
import kotlinx.coroutines.*
import java.awt.Dimension
import java.util.*

class PromptTextField(
    private val project: Project,
    private val tagManager: TagManager,
    private val onTextChanged: (String) -> Unit,
    private val onBackSpace: () -> Unit,
    private val onLookupAdded: (LookupActionItem) -> Unit,
    private val onSubmit: (String) -> Unit,
) : EditorTextField(project, FileTypes.PLAIN_TEXT), Disposable {

    companion object {
        private val logger = thisLogger()
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var showSuggestionsJob: Job? = null
    private var isInSearchContext = false
    private var isInGroupLookupContext = false
    private var lastSearchText: String? = null
    private var lastSearchResults: List<LookupActionItem>? = null

    val dispatcherId: UUID = UUID.randomUUID()
    var lookup: LookupImpl? = null

    init {
        logger.info("PromptTextField initialized with dispatcherId: $dispatcherId")
        isOneLineMode = false
        IS_PROMPT_TEXT_FIELD_DOCUMENT.set(document, true)
        setPlaceholder(CodeGPTBundle.get("toolwindow.chat.textArea.emptyText"))
    }

    override fun onEditorAdded(editor: Editor) {
        logger.info("Editor added for PromptTextField")
        IdeEventQueue.getInstance().addDispatcher(
            PromptTextFieldEventDispatcher(dispatcherId, onBackSpace, lookup) { event ->
                val shown = lookup?.let { it.isShown && !it.isLookupDisposed } == true
                logger.info("Submit attempt - lookup shown: $shown, text length: ${text.length}")
                if (shown) {
                    logger.info("Submit blocked due to active lookup")
                    return@PromptTextFieldEventDispatcher
                }

                logger.info("Submitting text: '${text.take(50)}${if (text.length > 50) "..." else ""}'")
                onSubmit(text)
                event.consume()
            },
            this
        )
    }

    fun clear() {
        runInEdt {
            text = ""
        }
    }

    suspend fun showGroupLookup() {
        logger.info("showGroupLookup() called")
        val lookupItems = listOf(
            FilesGroupItem(project, tagManager),
            FoldersGroupItem(project, tagManager),
            GitGroupItem(project),
            PersonasGroupItem(tagManager),
            DocsGroupItem(tagManager),
            MCPGroupItem(),
            WebActionItem(tagManager)
        )
            .filter { it.enabled }
            .map { it.createLookupElement() }
            .toTypedArray()

        logger.info("Created ${lookupItems.size} group lookup items")

        withContext(Dispatchers.Main) {
            editor?.let {
                logger.info("Showing group lookup in main context")
                showGroupLookup(it, lookupItems)
            } ?: logger.info("Editor is null, cannot show group lookup")
        }
    }

    private fun showGroupLookup(editor: Editor, lookupElements: Array<LookupElement>) {
        logger.info("showGroupLookup() called with ${lookupElements.size} elements")
        isInGroupLookupContext = false
        logger.info("Set isInGroupLookupContext = false")

        lookup = createLookup(editor, lookupElements, "")

        lookup?.addLookupListener(object : LookupListener {
            override fun itemSelected(event: LookupEvent) {
                val lookupString = event.item?.lookupString ?: return
                val suggestion = event.item?.getUserData(LookupItem.KEY) ?: return
                logger.info("Group lookup item selected: '$lookupString', suggestion type: ${suggestion::class.simpleName}")

                val offset = editor.caretModel.offset
                val start = offset - lookupString.length
                logger.info("Replacing text from $start to $offset")

                if (start >= 0) {
                    runUndoTransparentWriteAction {
                        editor.document.deleteString(start, offset)
                    }
                }

                if (suggestion is WebActionItem) {
                    logger.info("WebActionItem selected, calling onLookupAdded")
                    onLookupAdded(suggestion)
                }

                if (suggestion !is LookupGroupItem) {
                    logger.info("Selected item is not a LookupGroupItem, returning")
                    return
                }

                logger.info("Selected LookupGroupItem: ${suggestion::class.simpleName}")
                showSuggestionsJob?.cancel()
                showSuggestionsJob = coroutineScope.launch {
                    logger.info("Launching showGroupSuggestions coroutine")
                    showGroupSuggestions(suggestion)
                }
            }

            override fun lookupCanceled(event: LookupEvent) {
                logger.info("Group lookup canceled")
                isInGroupLookupContext = false
            }
        })
        lookup?.refreshUi(false, true)
        lookup?.showLookup()
        logger.info("Group lookup shown")
    }

    private fun showGlobalSearchResults(
        results: List<LookupActionItem>,
        searchText: String
    ) {
        logger.info("showGlobalSearchResults() called with ${results.size} results for search: '$searchText'")
        editor?.let { editor ->
            try {
                val lookupElements = results.map { it.createLookupElement() }.toTypedArray()
                logger.info("Created ${lookupElements.size} lookup elements")

                val existingLookup = lookup
                if (existingLookup != null && existingLookup.isShown && !existingLookup.isLookupDisposed) {
                    logger.info("Hiding existing lookup before creating new one")
                    existingLookup.hide()
                }

                logger.info("Creating new global search lookup")
                lookup = createLookup(editor, lookupElements, "")

                lookup?.addLookupListener(object : LookupListener {
                    override fun itemSelected(event: LookupEvent) {
                        val lookupItem = event.item?.getUserData(LookupItem.KEY) ?: return
                        logger.info("Global search item selected: ${lookupItem::class.simpleName}")

                        if (lookupItem !is LookupActionItem) {
                            logger.info("Selected item is not a LookupActionItem, returning")
                            return
                        }

                        logger.info("Replacing @ symbol with search result")
                        replaceAtSymbolWithSearch(editor, lookupItem, searchText)
                        onLookupAdded(lookupItem)
                    }
                })

                lookup?.refreshUi(false, true)
                lookup?.showLookup()
                logger.info("Global search lookup shown")
            } catch (e: Exception) {
                logger.error("Error showing lookup: $e", e)
            }
        } ?: logger.info("Editor is null, cannot show global search results")
    }

    private fun replaceAtSymbolWithSearch(
        editor: Editor,
        lookupItem: LookupItem,
        searchText: String
    ) {
        logger.info("replaceAtSymbolWithSearch() called for item: ${lookupItem::class.simpleName}, searchText: '$searchText'")
        val atPos = findAtSymbolPosition(editor)
        logger.info("@ symbol position: $atPos")

        if (atPos >= 0) {
            runUndoTransparentWriteAction {
                val shouldInsertDisplayName = lookupItem is FileActionItem
                        || lookupItem is FolderActionItem
                        || lookupItem is GitCommitActionItem
                logger.info("Should insert display name: $shouldInsertDisplayName")

                if (shouldInsertDisplayName) {
                    val endPos = atPos + 1 + searchText.length
                    logger.info("Replacing text from $atPos to $endPos with '${lookupItem.displayName}'")
                    editor.document.deleteString(atPos, endPos)
                    editor.document.insertString(atPos, lookupItem.displayName)
                    editor.caretModel.moveToOffset(atPos + lookupItem.displayName.length)
                    editor.markupModel.addRangeHighlighter(
                        atPos,
                        atPos + lookupItem.displayName.length,
                        HighlighterLayer.SELECTION,
                        TextAttributes().apply {
                            foregroundColor = JBColor(0x00627A, 0xCC7832)
                        },
                        HighlighterTargetArea.EXACT_RANGE
                    )
                } else {
                    val endPos = atPos + 1 + searchText.length
                    logger.info("Deleting text from $atPos to $endPos")
                    editor.document.deleteString(atPos, endPos)
                }
            }
        } else {
            logger.info("@ symbol not found, cannot replace")
        }
    }

    private fun findAtSymbolPosition(editor: Editor): Int {
        val atPos = editor.document.text.lastIndexOf('@')
        return if (atPos >= 0) atPos else -1
    }

    private suspend fun showGroupSuggestions(group: LookupGroupItem) {
        logger.info("showGroupSuggestions() called for group: ${group::class.simpleName}")
        val suggestions = group.getLookupItems()
        logger.info("Retrieved ${suggestions.size} suggestions from group")

        if (suggestions.isEmpty()) {
            logger.info("No suggestions found, returning")
            return
        }

        val lookupElements = suggestions.map { it.createLookupElement() }.toTypedArray()
        logger.info("Created ${lookupElements.size} lookup elements from suggestions")

        withContext(Dispatchers.Main) {
            logger.info("Showing suggestion lookup in main context")
            showSuggestionLookup(lookupElements, group)
        }
    }

    private fun createLookup(
        editor: Editor,
        lookupElements: Array<LookupElement>,
        searchText: String
    ) = runReadAction {
        LookupManager.getInstance(project).createLookup(
            editor,
            lookupElements,
            searchText,
            LookupArranger.DefaultArranger()
        ) as LookupImpl
    }

    private fun showSuggestionLookup(
        lookupElements: Array<LookupElement>,
        parentGroup: LookupGroupItem,
        filterText: String = "",
    ) {
        editor?.let {
            isInGroupLookupContext = true
            lookup = createLookup(it, lookupElements, filterText)
            lookup?.addLookupListener(object : LookupListener {
                override fun itemSelected(event: LookupEvent) {
                    val lookupItem = event.item?.getUserData(LookupItem.KEY) ?: return
                    if (lookupItem !is LookupActionItem) return

                    replaceAtSymbol(it, lookupItem)
                    onLookupAdded(lookupItem)
                }

                override fun lookupCanceled(event: LookupEvent) {
                    isInGroupLookupContext = false
                }

                private fun replaceAtSymbol(editor: Editor, lookupItem: LookupItem) {
                    val offset = editor.caretModel.offset
                    val start = findAtSymbolPosition(editor)
                    if (start >= 0) {
                        runUndoTransparentWriteAction {
                            val shouldInsertDisplayName = lookupItem is FileActionItem
                                    || lookupItem is FolderActionItem
                                    || lookupItem is GitCommitActionItem
                            if (shouldInsertDisplayName) {
                                editor.document.deleteString(start, offset)
                                editor.document.insertString(start, lookupItem.displayName)
                                editor.caretModel.moveToOffset(start + lookupItem.displayName.length)
                                editor.markupModel.addRangeHighlighter(
                                    start,
                                    start + lookupItem.displayName.length,
                                    HighlighterLayer.SELECTION,
                                    TextAttributes().apply {
                                        foregroundColor = JBColor(0x00627A, 0xCC7832)
                                    },
                                    HighlighterTargetArea.EXACT_RANGE
                                )
                            } else {
                                editor.document.deleteString(start, offset)
                            }
                        }
                    }
                }
            })

            lookup?.addPrefixChangeListener(object : PrefixChangeListener {
                override fun afterAppend(c: Char) {
                    showSuggestionsJob?.cancel()
                    showSuggestionsJob = coroutineScope.launch {
                        if (parentGroup is DynamicLookupGroupItem) {
                            val searchText = getSearchText()
                            if (searchText.length == 2) {
                                parentGroup.updateLookupList(lookup!!, searchText)
                            }
                        }
                    }
                }

                override fun afterTruncate() {
                    if (parentGroup is DynamicLookupGroupItem) {
                        val searchText = getSearchText()
                        if (searchText.isEmpty()) {
                            showSuggestionLookup(lookupElements, parentGroup, filterText)
                        }
                    }
                }

                private fun getSearchText(): String {
                    val text = it.document.text
                    return text.substring(text.lastIndexOf("@") + 1)
                }

            }, this)

            lookup?.refreshUi(false, true)
            lookup?.showLookup()
        }
    }

    override fun createEditor(): EditorEx {
        val editorEx = super.createEditor()
        editorEx.settings.isUseSoftWraps = true
        editorEx.backgroundColor = service<EditorColorsManager>().globalScheme.defaultBackground
        setupDocumentListener(editorEx)
        return editorEx
    }

    override fun updateBorder(editor: EditorEx) {
        editor.setBorder(JBUI.Borders.empty(4, 8))
    }

    override fun dispose() {
        showSuggestionsJob?.cancel()
        lastSearchResults = null
    }

    private fun setupDocumentListener(editor: EditorEx) {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                logger.info("Document changed - offset: ${event.offset}, newLength: ${event.newLength}, fragment: '${event.newFragment}'")
                adjustHeight(editor)
                onTextChanged(event.document.text)

                val text = editor.document.text
                val caretOffset = event.offset + event.newLength
                logger.info("Current text length: ${text.length}, caret offset: $caretOffset")

                if ("@" == event.newFragment.toString()) {
                    logger.info("@ symbol detected, entering search context")
                    isInSearchContext = true
                    lastSearchText = ""

                    showSuggestionsJob?.cancel()
                    logger.info("Cancelled previous suggestions job")

                    showSuggestionsJob = coroutineScope.launch {
                        logger.info("Launching showGroupLookup coroutine")
                        showGroupLookup()
                    }
                } else {
                    val searchText = getSearchTextAfterAt(text, caretOffset)
                    logger.info("Extracted search text: '$searchText'")

                    when {
                        searchText != null && searchText.isEmpty() -> {
                            logger.info("Empty search text detected - reverting to group lookup")
                            if (!isInSearchContext || lastSearchText != searchText) {
                                logger.info("State change needed - updating to group lookup")
                                isInSearchContext = true
                                lastSearchText = searchText
                                isInGroupLookupContext = false

                                showSuggestionsJob?.cancel()
                                logger.info("Cancelled previous job, launching updateLookupWithGroups")
                                showSuggestionsJob = coroutineScope.launch {
                                    updateLookupWithGroups()
                                }
                            } else {
                                logger.info("No state change needed for empty search")
                            }
                        }

                        !searchText.isNullOrEmpty() -> {
                            logger.info("Non-empty search text: '$searchText'")
                            // Skip global search logic if we're in a specific group lookup context
                            if (!isInGroupLookupContext) {
                                logger.info("Not in group lookup context, checking if matches default groups")
                                // Only trigger global search if searchText doesn't match any default groups
                                if (!matchesAnyDefaultGroup(searchText)) {
                                    logger.info("Search text doesn't match default groups, triggering global search")
                                    if (!isInSearchContext || lastSearchText != searchText) {
                                        logger.info("State change needed for global search")
                                        isInSearchContext = true
                                        lastSearchText = searchText
                                        logger.info("Updated search state: lastSearchText='$lastSearchText'")

                                        showSuggestionsJob?.cancel()
                                        logger.info("Launching global search with 200ms delay")
                                        showSuggestionsJob = coroutineScope.launch {
                                            delay(200)
                                            updateLookupWithSearchResults(searchText)
                                        }
                                    } else {
                                        logger.info("No state change needed for global search")
                                    }
                                } else {
                                    logger.info("Search text matches default group, skipping global search")
                                }
                            } else {
                                logger.info("In group lookup context, skipping global search logic")
                            }
                        }

                        searchText == null -> {
                            logger.info("No search text found, exiting search context")
                            if (isInSearchContext) {
                                logger.info("Was in search context, cleaning up")
                                isInSearchContext = false
                                isInGroupLookupContext = false
                                lastSearchText = null

                                showSuggestionsJob?.cancel()
                                logger.info("Cancelled suggestions job")

                                lookup?.let { existingLookup ->
                                    if (!existingLookup.isLookupDisposed && existingLookup.isShown) {
                                        logger.info("Hiding existing lookup")
                                        runInEdt { existingLookup.hide() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }, this)
    }

    private suspend fun updateLookupWithGroups() {
        logger.info("updateLookupWithGroups() called")
        val lookupItems = listOf(
            FilesGroupItem(project, tagManager),
            FoldersGroupItem(project, tagManager),
            GitGroupItem(project),
            PersonasGroupItem(tagManager),
            DocsGroupItem(tagManager),
            MCPGroupItem(),
            WebActionItem(tagManager)
        )
            .filter { it.enabled }
            .map { it.createLookupElement() }
            .toTypedArray()

        logger.info("Created ${lookupItems.size} group items for update")

        withContext(Dispatchers.Main) {
            editor?.let { editor ->
                logger.info("Editor available for group update")
                lookup?.let {
                    logger.info("Existing lookup found - isShown: ${it.isShown}, isDisposed: ${it.isLookupDisposed}")
                    if (it.isShown && !it.isLookupDisposed) {
                        val wasShown = it.isShown
                        logger.info("Hiding existing lookup (wasShown: $wasShown)")
                        it.hide()

                        if (wasShown) {
                            logger.info("Showing new group lookup after hiding previous")
                            showGroupLookup(editor, lookupItems)
                        }
                    } else {
                        logger.info("Showing group lookup (no previous lookup active)")
                        showGroupLookup(editor, lookupItems)
                    }
                } ?: logger.info("No existing lookup, showing new group lookup")
            } ?: logger.info("Editor not available for group update")
        }
    }

    private suspend fun updateLookupWithSearchResults(searchText: String) {
        logger.info("updateLookupWithSearchResults() called with searchText: '$searchText'")

        val allGroups = listOf(
            FilesGroupItem(project, tagManager),
            FoldersGroupItem(project, tagManager),
            GitGroupItem(project),
            PersonasGroupItem(tagManager),
            DocsGroupItem(tagManager),
            MCPGroupItem()
        ).filter { it.enabled }

        val allResults = mutableListOf<LookupActionItem>()
        allGroups.forEach { group ->
            try {
                val lookupActionItems =
                    group.getLookupItems("")
                        .filterIsInstance<LookupActionItem>() // Get all items, filter later
                allResults.addAll(lookupActionItems)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error getting results from ${group::class.simpleName}", e)
            }
        }

        val webAction = WebActionItem(tagManager)
        if (webAction.enabled()) {
            allResults.add(webAction)
        }

        val matcher: MinusculeMatcher = NameUtil.buildMatcher("*$searchText").build()
        val matchedResults = allResults.mapNotNull { result ->
            if (result is WebActionItem) {
                if (searchText.contains("web", ignoreCase = true)) {
                    result to 100
                } else null
            } else {
                val matchingDegree = matcher.matchingDegree(result.displayName)
                if (matchingDegree != Int.MIN_VALUE) {
                    result to matchingDegree
                } else null
            }
        }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(100) // Limit results for better performance

        logger.info("Filtered to ${matchedResults.size} matching results for '$searchText'")

        // Only update lookup if results have actually changed
        if (lastSearchResults != matchedResults) {
            lastSearchResults = matchedResults
            withContext(Dispatchers.Main) {
                showGlobalSearchResults(matchedResults, searchText)
            }
        } else {
            logger.info("Results unchanged, skipping lookup update")
        }
    }

    private fun adjustHeight(editor: EditorEx) {
        val contentHeight = editor.contentComponent.preferredSize.height + 8
        val maxHeight = JBUI.scale(getToolWindowHeight() / 2)
        val newHeight = minOf(contentHeight, maxHeight)

        runInEdt {
            preferredSize = Dimension(width, newHeight)
            editor.setVerticalScrollbarVisible(contentHeight > maxHeight)
            parent?.revalidate()
        }
    }

    private fun getToolWindowHeight(): Int {
        return project.service<ToolWindowManager>()
            .getToolWindow("ProxyAI")?.component?.visibleRect?.height ?: 400
    }

    private fun getSearchTextAfterAt(text: String, caretOffset: Int): String? {
        val atPos = text.lastIndexOf('@')
        if (atPos == -1 || atPos >= caretOffset) return null

        val searchText = text.substring(atPos + 1, caretOffset)
        return if (searchText.contains(' ') || searchText.contains('\n')) {
            null
        } else {
            searchText
        }
    }

    private fun matchesAnyDefaultGroup(searchText: String): Boolean {
        val defaultGroupNames = listOf(
            "files", "file", "f",
            "folders", "folder", "fold",
            "git", "g",
            "personas", "persona", "p",
            "docs", "doc", "d",
            "mcp", "m",
            "web", "w"
        )

        return defaultGroupNames.any { groupName ->
            groupName.startsWith(searchText, ignoreCase = true)
        }
    }
}