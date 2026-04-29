package ee.carlrobert.codegpt.ui.textarea

import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTKeys.IS_PROMPT_TEXT_FIELD_DOCUMENT
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.ui.dnd.FileDragAndDrop
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.*
import kotlinx.coroutines.*
import java.awt.Cursor
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.util.*
import javax.swing.JComponent
import javax.swing.TransferHandler
import kotlin.time.Duration.Companion.milliseconds

private enum class LookupDisplayMode {
    GROUPS,
    GROUP_RESULTS,
    GLOBAL_SEARCH
}

private data class PendingLookupSearch(
    val generation: Long,
    val mode: LookupDisplayMode,
    val searchText: String,
    val group: LookupGroupItem? = null
)

class PromptTextField(
    private val project: Project,
    tagManager: TagManager,
    private val onTextChanged: (String) -> Unit,
    private val onBackSpace: () -> Unit,
    private val onLookupAdded: (LookupActionItem) -> Unit,
    private val onLookupSearchLoadingChanged: (Boolean) -> Unit = {},
    private val onSubmit: (String) -> Unit,
    private val onFilesDropped: (List<VirtualFile>) -> Unit = {},
    featureType: FeatureType? = null,
    document: Document = EditorFactory.getInstance().createDocument("").apply {
        IS_PROMPT_TEXT_FIELD_DOCUMENT.set(this, true)
    },
) : EditorTextField(document, project, FileTypes.PLAIN_TEXT, false, false), Disposable {

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val lookupManager = PromptTextFieldLookupManager(project, onLookupAdded)
    private val searchManager = SearchManager(project, tagManager, featureType)

    @Volatile
    private var isFieldDisposed = false

    private var mouseClickListener: MouseAdapter? = null
    private var mouseMotionListener: MouseMotionAdapter? = null

    private var showSuggestionsJob: Job? = null
    private var searchState = SearchState()
    private var activeLookupGroup: LookupGroupItem? = null
    private var lookupMode: LookupDisplayMode? = null
    private var lookupSearchText: String? = null
    private var lookupSearchLoading = false
    private var lookupSearchLoadingGeneration = 0L
    private var lookupSearchLoadingShownAt: Long? = null
    private var lookupSearchLoadingJob: Job? = null
    private var pendingLookupSearch: PendingLookupSearch? = null
    private var lastVisibleLookupItems: List<LookupItem> = emptyList()
    private var lastVisibleLookupItemsSearchText: String? = null
    private var lastVisibleLookupItemsMode: LookupDisplayMode? = null
    private var lastVisibleLookupItemsGroup: LookupGroupItem? = null
    private val globalSearchTextProvider: () -> String = { lookupSearchText.orEmpty() }

    val dispatcherId: UUID = UUID.randomUUID()
    var lookup: LookupImpl? = null

    init {
        isOneLineMode = false
        document.putUserData(PROMPT_FIELD_KEY, this)
        setPlaceholder(CodeGPTBundle.get("toolwindow.chat.textArea.emptyText"))

        installEditorInputHandlers()
    }

    override fun onEditorAdded(editor: Editor) {
        if (isFieldDisposed) {
            return
        }
        IdeEventQueue.getInstance().addDispatcher(
            PromptTextFieldEventDispatcher(dispatcherId, onBackSpace) { event ->
                val shown = lookup?.let { it.isShown && !it.isLookupDisposed } == true
                if (shown) {
                    return@PromptTextFieldEventDispatcher
                }

                onSubmit(getExpandedText())
                event.consume()
            },
            this
        )
        val highlightTarget = (parent as? JComponent) ?: this
        FileDragAndDrop.install(editor.contentComponent, highlightTarget) { onFilesDropped(it) }

        val contentComponent = editor.contentComponent
        val previousHandler = contentComponent.transferHandler
        contentComponent.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(DataFlavor.stringFlavor) ||
                        (previousHandler?.canImport(support) == true)
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!support.isDrop && support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    val pasted = try {
                        support.transferable.getTransferData(DataFlavor.stringFlavor) as? String
                    } catch (_: Exception) {
                        null
                    }
                    if (!pasted.isNullOrEmpty()) {
                        insertPlaceholderFor(pasted)
                        return true
                    }
                    return true
                }
                return previousHandler?.importData(support) == true
            }
        }

        val clickListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val editor = this@PromptTextField.editor as? EditorEx ?: return
                val offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(e.point))
                val placeholder = findPlaceholderAtOffset(offset) ?: return
                val start = placeholder.highlighter.startOffset
                val end = placeholder.highlighter.endOffset
                runUndoTransparentWriteAction {
                    editor.document.replaceString(start, end, placeholder.content)
                    editor.caretModel.moveToOffset(start + placeholder.content.length)
                }
                editor.markupModel.removeHighlighter(placeholder.highlighter)
                placeholders.remove(placeholder)
            }
        }
        mouseClickListener = clickListener
        editor.contentComponent.addMouseListener(clickListener)

        val motionListener = object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val editor = this@PromptTextField.editor as? EditorEx ?: return
                val offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(e.point))
                val inside = findPlaceholderAtOffset(offset) != null
                val component = editor.contentComponent
                component.cursor =
                    if (inside) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    else Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                component.toolTipText = if (inside) "Click to edit" else null
            }
        }
        mouseMotionListener = motionListener
        editor.contentComponent.addMouseMotionListener(motionListener)
    }

    fun clear() {
        runInEdt {
            text = ""
            clearPlaceholders()
        }
    }

    fun setTextAndFocus(text: String) {
        runInEdt {
            this.text = text
            requestFocusInWindow()
        }
    }

    suspend fun showGroupLookup() {
        val lookupItems = searchManager.getDefaultGroups()
            .map { it.createLookupElement() }
            .toTypedArray()

        withContext(Dispatchers.Main) {
            editor?.let { editor ->
                hideLookupIfShown()
                lookup = lookupManager.showGroupLookup(
                    editor = editor,
                    lookupElements = lookupItems,
                    onGroupSelected = { group ->
                        handleGroupSelected(group)
                    },
                    onWebActionSelected = { webAction ->
                        onLookupAdded(webAction)
                    },
                    onCodeAnalyzeSelected = { codeAnalyzeAction ->
                        onLookupAdded(codeAnalyzeAction)
                    }
                )
                lookupMode = LookupDisplayMode.GROUPS
                lookupSearchText = null
            }
        }
    }

    private fun showGlobalSearchResults(
        results: List<LookupActionItem>,
        searchText: String,
        isCalculating: Boolean = false,
        generation: Long? = null,
        showEmptyStatus: Boolean = true
    ) {
        editor?.let { editor ->
            try {
                if (generation != null && generation != lookupSearchLoadingGeneration) {
                    return
                }
                val currentSearchText = runReadActionBlocking {
                    searchManager.getSearchTextAfterAt(
                        editor.document.text,
                        editor.caretModel.offset
                    )
                }
                if (currentSearchText != searchText) {
                    return
                }

                val existingLookup = lookup
                val canReuseGlobalLookup = lookupMode == LookupDisplayMode.GLOBAL_SEARCH &&
                        existingLookup != null &&
                        existingLookup.isShown &&
                        !existingLookup.isLookupDisposed

                val previousItems = getRelatedVisibleLookupItems(
                    LookupDisplayMode.GLOBAL_SEARCH,
                    searchText,
                    group = null
                )
                val lookupItems = toSearchLookupItems(
                    results,
                    searchText,
                    isCalculating,
                    previousItems,
                    showEmptyStatus
                )

                if (canReuseGlobalLookup) {
                    lookupSearchText = searchText
                    lookupManager.updateSearchResultsLookup(
                        existingLookup,
                        lookupItems,
                        searchText,
                        isCalculating,
                        globalSearchTextProvider,
                        matcherPrefix = getLookupPrefix(editor)
                    )
                    searchManager.cacheSearchResults(searchText, results)
                    rememberVisibleLookupItems(
                        LookupDisplayMode.GLOBAL_SEARCH,
                        searchText,
                        group = null,
                        lookupItems
                    )
                    return
                }

                if (lookupItems.isEmpty() && existingLookup != null) {
                    return
                }

                run {
                    val previouslySelectedKey = existingLookup
                        ?.takeIf { it.isShown && !it.isLookupDisposed }
                        ?.let(lookupManager::getSelectedLookupItemKey)
                    hideLookupIfShown()
                    lookupSearchText = searchText
                    lookup = lookupManager.showSearchResultsLookup(
                        editor,
                        lookupItems,
                        searchText,
                        isCalculating,
                        globalSearchTextProvider,
                        lookupPrefix = getLookupPrefix(editor)
                    )
                    lookupMode = LookupDisplayMode.GLOBAL_SEARCH
                    lookup?.let {
                        lookupManager.restoreSelectedLookupItem(
                            it,
                            previouslySelectedKey
                        )
                        addLookupCleanupListener(it)
                    }
                }

                searchManager.cacheSearchResults(searchText, results)
                rememberVisibleLookupItems(
                    LookupDisplayMode.GLOBAL_SEARCH,
                    searchText,
                    group = null,
                    lookupItems
                )
            } catch (e: Exception) {
                logger.error("Error showing lookup: $e", e)
            }
        }
    }

    private fun handleGroupSelected(group: LookupGroupItem) {
        activeLookupGroup = group
        searchManager.clearSearchResultsCache()
        clearVisibleLookupItems()
        searchState = searchState.copy(
            isInSearchContext = true,
            isInGroupLookupContext = true,
            lastSearchText = ""
        )
        showSuggestionsJob?.cancel()
        showSuggestionsJob = coroutineScope.launch {
            showGroupSuggestions(group)
        }
    }

    private suspend fun showGroupSuggestions(
        group: LookupGroupItem,
        searchText: String = ""
    ) {
        val suggestions = group.getLookupItems(searchText)
        if (suggestions.isEmpty()) {
            return
        }

        withContext(Dispatchers.Main) {
            showSuggestionLookup(suggestions, searchText)
        }
    }

    private fun showSuggestionLookup(
        lookupItems: List<LookupItem>,
        searchText: String = ""
    ) {
        editor?.let { editor ->
            searchState = searchState.copy(isInGroupLookupContext = true)

            lookup = lookupManager.showSuggestionLookup(
                editor = editor,
                lookupItems = lookupItems,
                searchText = searchText,
                lookupPrefix = getLookupPrefix(editor),
            )
            lookupMode = LookupDisplayMode.GROUP_RESULTS
            lookupSearchText = null
            rememberVisibleLookupItems(
                LookupDisplayMode.GROUP_RESULTS,
                searchText,
                activeLookupGroup,
                lookupItems
            )

            lookup?.addLookupListener(object : LookupListener {
                override fun lookupCanceled(event: LookupEvent) {
                    searchState = searchState.copy(isInGroupLookupContext = false)
                    lookupMode = null
                    lookupSearchText = null
                    clearVisibleLookupItems()
                }

                override fun itemSelected(event: LookupEvent) {
                    searchState = searchState.copy(isInGroupLookupContext = false)
                    lookupMode = null
                    lookupSearchText = null
                    clearVisibleLookupItems()
                }
            })
        }
    }

    override fun createEditor(): EditorEx {
        val editorEx = super.createEditor()
        if (isFieldDisposed) {
            return editorEx
        }
        editorEx.settings.isUseSoftWraps = true
        editorEx.backgroundColor = service<EditorColorsManager>().globalScheme.defaultBackground
        setupDocumentListener(editorEx)
        adjustHeight(editorEx)
        return editorEx
    }

    override fun updateBorder(editor: EditorEx) {
        editor.setBorder(
            JBUI.Borders.empty(
                PromptTextFieldConstants.BORDER_PADDING,
                PromptTextFieldConstants.BORDER_SIDE_PADDING
            )
        )
    }

    override fun dispose() {
        isFieldDisposed = true
        showSuggestionsJob?.cancel()
        stopLookupSearchLoading()
        lookupSearchLoadingJob?.cancel()
        clearPlaceholders()
        val ed = this.editor
        mouseClickListener?.let { l -> ed?.contentComponent?.removeMouseListener(l) }
        mouseMotionListener?.let { l -> ed?.contentComponent?.removeMouseMotionListener(l) }
    }

    fun insertPlaceholderFor(pastedText: String) {
        val editor = editor as? EditorEx ?: return
        if (pastedText.isEmpty()) return

        if (pastedText.length <= PromptTextFieldConstants.PASTE_PLACEHOLDER_MIN_LENGTH) {
            runUndoTransparentWriteAction { replaceSelectionOrInsert(editor, pastedText) }
            return
        }

        val placeholderLabel = " Pasted Content ${pastedText.length} chars "
        runUndoTransparentWriteAction {
            val (start, end) = replaceSelectionOrInsert(editor, placeholderLabel)
            addPastePlaceholder(editor, start, end, placeholderLabel, pastedText)
        }
    }

    private fun replaceSelectionOrInsert(editor: EditorEx, text: String): Pair<Int, Int> {
        val document = editor.document
        val caret = editor.caretModel
        val selectionModel = editor.selectionModel
        val start: Int
        if (selectionModel.hasSelection()) {
            val selectionStart = selectionModel.selectionStart
            val selectionEnd = selectionModel.selectionEnd
            document.replaceString(selectionStart, selectionEnd, text)
            selectionModel.removeSelection()
            start = selectionStart
        } else {
            start = caret.offset
            document.insertString(start, text)
        }
        val end = start + text.length
        caret.moveToOffset(end)
        return start to end
    }

    private fun addPastePlaceholder(
        editor: EditorEx,
        start: Int,
        end: Int,
        label: String,
        content: String
    ) {
        val attrs = TextAttributes().apply {
            backgroundColor = JBColor(0xF2F4F7, 0x2B2D30)
            effectType = EffectType.ROUNDED_BOX
            effectColor = JBColor(0xC4C9D0, 0x44484F)
        }
        val highlighter = editor.markupModel.addRangeHighlighter(
            start,
            end,
            HighlighterLayer.ADDITIONAL_SYNTAX,
            attrs,
            HighlighterTargetArea.EXACT_RANGE
        )
        highlighter.isGreedyToLeft = false
        highlighter.isGreedyToRight = false
        placeholders.add(PastePlaceholder(highlighter, label, content))
    }

    private data class PastePlaceholder(
        val highlighter: RangeHighlighter,
        val label: String,
        var content: String
    )

    private val placeholders: MutableList<PastePlaceholder> = mutableListOf()

    fun getExpandedText(): String {
        val text = document.text
        if (placeholders.isEmpty()) return text
        val validPlaceholders =
            placeholders.filter { it.highlighter.isValid }.sortedBy { it.highlighter.startOffset }
        if (validPlaceholders.isEmpty()) return text
        val result = StringBuilder()
        var cursor = 0
        for (placeholder in validPlaceholders) {
            val start = placeholder.highlighter.startOffset
            val end = placeholder.highlighter.endOffset
            if (start < cursor || start > text.length || end > text.length) continue
            if (cursor < start) result.append(text, cursor, start)
            val span = text.substring(start, end)
            if (span == placeholder.label) result.append(placeholder.content) else result.append(
                span
            )
            cursor = end
        }
        if (cursor < text.length) result.append(text.substring(cursor))
        return result.toString()
    }

    private fun findPlaceholderAtOffset(offset: Int): PastePlaceholder? {
        return placeholders.firstOrNull { ph ->
            ph.highlighter.isValid && offset >= ph.highlighter.startOffset && offset < ph.highlighter.endOffset
        }
    }

    private fun findPlaceholdersIntersecting(start: Int, end: Int): List<PastePlaceholder> {
        return placeholders.filter { ph ->
            ph.highlighter.isValid && ph.highlighter.startOffset < end && ph.highlighter.endOffset > start
        }
    }

    private fun clearPlaceholders() {
        val ed = this.editor as? EditorEx ?: return
        placeholders.forEach { ph -> ed.markupModel.removeHighlighter(ph.highlighter) }
        placeholders.clear()
    }

    fun handlePlaceholderDelete(isBackspace: Boolean): Boolean {
        val editor = editor as? EditorEx ?: return false
        val document = editor.document
        val caret = editor.caretModel
        val selectionModel = editor.selectionModel

        if (selectionModel.hasSelection()) {
            val selStart = selectionModel.selectionStart
            val selEnd = selectionModel.selectionEnd
            val intersecting = findPlaceholdersIntersecting(selStart, selEnd)
            if (intersecting.isNotEmpty()) {
                val newStart = minOf(selStart, intersecting.minOf { it.highlighter.startOffset })
                val newEnd = maxOf(selEnd, intersecting.maxOf { it.highlighter.endOffset })
                runUndoTransparentWriteAction {
                    document.deleteString(newStart, newEnd)
                    caret.moveToOffset(newStart)
                }
                intersecting.forEach { ph -> editor.markupModel.removeHighlighter(ph.highlighter) }
                placeholders.removeAll(intersecting.toSet())
                selectionModel.removeSelection()
                return true
            }
            return false
        }

        val offset = caret.offset
        val target = if (isBackspace) (if (offset > 0) offset - 1 else offset) else offset
        val placeholder = findPlaceholderAtOffset(target) ?: return false
        val start = placeholder.highlighter.startOffset
        val end = placeholder.highlighter.endOffset
        runUndoTransparentWriteAction {
            document.deleteString(start, end)
            caret.moveToOffset(start)
        }
        editor.markupModel.removeHighlighter(placeholder.highlighter)
        placeholders.remove(placeholder)
        return true
    }

    private fun setupDocumentListener(editor: EditorEx) {
        if (isFieldDisposed) {
            return
        }

        try {
            editor.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    adjustHeight(editor)
                    onTextChanged(event.document.text)
                    handleDocumentChange(event)
                }
            }, this)
        } catch (e: IncorrectOperationException) {
            if (!isFieldDisposed) {
                throw e
            }
            logger.debug("Skipping document listener registration for disposed PromptTextField", e)
        }
    }

    private fun handleDocumentChange(event: DocumentEvent) {
        prunePlaceholders(event)
        val text = event.document.text
        val caretOffset = event.offset + event.newLength

        when {
            isAtSymbolTyped(event) -> handleAtSymbolTyped()
            else -> handleTextChange(text, caretOffset)
        }
    }

    private fun prunePlaceholders(event: DocumentEvent) {
        if (placeholders.isEmpty()) return

        val editor = editor as? EditorEx ?: return
        val document = event.document
        val textLength = document.textLength
        val placeholdersToRemove = mutableListOf<PastePlaceholder>()
        for (placeholder in placeholders) {
            val highlighter = placeholder.highlighter
            if (!highlighter.isValid) {
                placeholdersToRemove.add(placeholder)
                continue
            }
            val start = highlighter.startOffset
            val end = highlighter.endOffset
            if (start < 0 || end > textLength || start >= end) {
                placeholdersToRemove.add(placeholder)
                continue
            }
            val span = try {
                document.charsSequence.subSequence(start, end).toString()
            } catch (_: Exception) {
                null
            }
            if (span == null || span != placeholder.label) {
                placeholdersToRemove.add(placeholder)
            }
        }
        if (placeholdersToRemove.isNotEmpty()) {
            placeholdersToRemove.forEach { placeholder ->
                editor.markupModel.removeHighlighter(placeholder.highlighter)
            }
            placeholders.removeAll(placeholdersToRemove.toSet())
        }
    }

    private fun isAtSymbolTyped(event: DocumentEvent): Boolean {
        return PromptTextFieldConstants.AT_SYMBOL == event.newFragment.toString()
    }

    private fun handleAtSymbolTyped() {
        activeLookupGroup = null
        searchManager.clearSearchResultsCache()
        stopLookupSearchLoading()
        clearVisibleLookupItems()
        searchState = searchState.copy(
            isInSearchContext = true,
            lastSearchText = ""
        )

        showSuggestionsJob?.cancel()
        showSuggestionsJob = coroutineScope.launch {
            showGroupLookup()
        }
    }

    private fun handleTextChange(text: String, caretOffset: Int) {
        val searchText = searchManager.getSearchTextAfterAt(text, caretOffset)
        when {
            searchText != null && activeLookupGroup != null -> handleActiveGroupSearch(searchText)
            searchText != null && searchText.isEmpty() -> handleEmptySearch()
            !searchText.isNullOrEmpty() -> handleNonEmptySearch(searchText)
            searchText == null && !searchState.isInGroupLookupContext -> handleNoSearch()
        }
    }

    private fun handleActiveGroupSearch(searchText: String) {
        val group = activeLookupGroup ?: return
        if (!searchState.isInSearchContext ||
            !searchState.isInGroupLookupContext ||
            searchState.lastSearchText != searchText
        ) {
            searchState = searchState.copy(
                isInSearchContext = true,
                isInGroupLookupContext = true,
                lastSearchText = searchText
            )

            scheduleGroupLookupRefresh(group, searchText)
        }
    }

    private fun scheduleGroupLookupRefresh(
        group: LookupGroupItem,
        searchText: String
    ) {
        val effectiveSearchText = getEffectiveGroupSearchText(group, searchText)
        showSuggestionsJob?.cancel()
        val loadingGeneration = if (effectiveSearchText.isNotEmpty()) {
            beginLookupSearchLoading(
                LookupDisplayMode.GROUP_RESULTS,
                effectiveSearchText,
                group
            )
        } else {
            null
        }
        showSuggestionsJob = coroutineScope.launch {
            try {
                if (effectiveSearchText.isNotEmpty()) {
                    delay(PromptTextFieldConstants.SEARCH_DELAY_MS.milliseconds)
                }
                updateLookupWithGroupResults(group, effectiveSearchText, loadingGeneration)
            } finally {
                loadingGeneration?.let(::finishLookupSearchLoading)
            }
        }
    }

    private fun getEffectiveGroupSearchText(
        group: LookupGroupItem,
        searchText: String
    ): String {
        return if (
            group is DynamicLookupGroupItem &&
            searchText.length < group.minimumSearchTextLength
        ) {
            ""
        } else {
            searchText
        }
    }

    private fun handleEmptySearch() {
        if (!searchState.isInSearchContext || searchState.lastSearchText != "") {
            searchState = searchState.copy(
                isInSearchContext = true,
                lastSearchText = "",
                isInGroupLookupContext = false
            )

            showSuggestionsJob?.cancel()
            stopLookupSearchLoading()
            showSuggestionsJob = coroutineScope.launch {
                updateLookupWithGroups()
            }
        }
    }

    private fun handleNonEmptySearch(searchText: String) {
        if (!searchState.isInGroupLookupContext) {
            if (!searchManager.matchesAnyDefaultGroup(searchText)) {
                if (!searchState.isInSearchContext || searchState.lastSearchText != searchText) {
                    searchState = searchState.copy(
                        isInSearchContext = true,
                        lastSearchText = searchText,
                        isInGroupLookupContext = false
                    )

                    showSuggestionsJob?.cancel()
                    val loadingGeneration = beginLookupSearchLoading(
                        LookupDisplayMode.GLOBAL_SEARCH,
                        searchText
                    )
                    showSuggestionsJob = coroutineScope.launch {
                        try {
                            val optimisticResults =
                                searchManager.getOptimisticSearchResults(searchText)
                            withContext(Dispatchers.Main) {
                                showGlobalSearchResults(
                                    optimisticResults,
                                    searchText,
                                    isCalculating = isLookupSearchLoadingVisible(loadingGeneration),
                                    generation = loadingGeneration,
                                    showEmptyStatus = false
                                )
                            }
                            updateLookupWithSearchResults(
                                searchText,
                                optimisticResults,
                                loadingGeneration
                            )
                        } finally {
                            finishLookupSearchLoading(loadingGeneration)
                        }
                    }
                }
            }
        }
    }

    private fun handleNoSearch() {
        if (searchState.isInSearchContext) {
            searchState = SearchState()
            activeLookupGroup = null
            showSuggestionsJob?.cancel()
            searchManager.clearSearchResultsCache()
            stopLookupSearchLoading()
            hideLookupIfShown()
        }
    }

    private fun beginLookupSearchLoading(
        mode: LookupDisplayMode,
        searchText: String,
        group: LookupGroupItem? = null
    ): Long {
        lookupSearchLoadingGeneration += 1
        val generation = lookupSearchLoadingGeneration
        val pendingSearch = PendingLookupSearch(generation, mode, searchText, group)
        pendingLookupSearch = pendingSearch
        lookupSearchLoadingJob?.cancel()
        lookupSearchLoadingJob = coroutineScope.launch {
            delay(PromptTextFieldConstants.LOOKUP_LOADING_REVEAL_MS.milliseconds)
            if (!isCurrentLookupSearch(pendingSearch)) {
                return@launch
            }
            setLookupSearchLoading(true)
            refreshLookupLoadingState(pendingSearch, isCalculating = true)
        }
        return generation
    }

    private fun finishLookupSearchLoading(generation: Long) {
        if (lookupSearchLoadingGeneration == generation) {
            lookupSearchLoadingJob?.cancel()
            lookupSearchLoadingJob = coroutineScope.launch {
                val shownAt = lookupSearchLoadingShownAt
                if (lookupSearchLoading && shownAt != null) {
                    val elapsedMs = System.currentTimeMillis() - shownAt
                    val remainingMs =
                        PromptTextFieldConstants.LOOKUP_LOADING_MIN_VISIBLE_MS - elapsedMs
                    if (remainingMs > 0) {
                        delay(remainingMs.milliseconds)
                    }
                }
                if (lookupSearchLoadingGeneration == generation) {
                    val completedSearch = pendingLookupSearch
                    pendingLookupSearch = null
                    setLookupSearchLoading(false)
                    completedSearch?.let {
                        refreshLookupLoadingState(it, isCalculating = false)
                    }
                }
            }
        }
    }

    private fun stopLookupSearchLoading() {
        lookupSearchLoadingGeneration += 1
        lookupSearchLoadingJob?.cancel()
        pendingLookupSearch = null
        setLookupSearchLoading(false)
    }

    private fun setLookupSearchLoading(loading: Boolean) {
        if (lookupSearchLoading == loading) {
            return
        }
        lookupSearchLoading = loading
        lookupSearchLoadingShownAt = if (loading) System.currentTimeMillis() else null
        runInEdt {
            if (!isFieldDisposed) {
                onLookupSearchLoadingChanged(loading)
            }
        }
    }

    private fun isLookupSearchLoadingVisible(generation: Long?): Boolean {
        return generation != null &&
                lookupSearchLoading &&
                lookupSearchLoadingGeneration == generation
    }

    private fun isCurrentLookupSearch(search: PendingLookupSearch): Boolean {
        if (isFieldDisposed || lookupSearchLoadingGeneration != search.generation) {
            return false
        }
        val editor = editor ?: return false
        val currentSearchText = runReadActionBlocking {
            searchManager.getSearchTextAfterAt(
                editor.document.text,
                editor.caretModel.offset
            )
        } ?: return false

        return when (search.mode) {
            LookupDisplayMode.GLOBAL_SEARCH ->
                !searchState.isInGroupLookupContext && currentSearchText == search.searchText

            LookupDisplayMode.GROUP_RESULTS ->
                activeLookupGroup == search.group &&
                        search.group != null &&
                        getEffectiveGroupSearchText(search.group, currentSearchText) ==
                        search.searchText

            LookupDisplayMode.GROUPS -> false
        }
    }

    private fun refreshLookupLoadingState(
        search: PendingLookupSearch,
        isCalculating: Boolean
    ) {
        if (!isCurrentLookupSearch(search)) {
            return
        }
        when (search.mode) {
            LookupDisplayMode.GLOBAL_SEARCH -> {
                val cachedResults = if (isCalculating) {
                    emptyList()
                } else {
                    getRelatedVisibleLookupItems(
                        LookupDisplayMode.GLOBAL_SEARCH,
                        search.searchText,
                        group = null
                    ).filterIsInstance<LookupActionItem>()
                }
                showGlobalSearchResults(
                    cachedResults,
                    search.searchText,
                    isCalculating,
                    search.generation
                )
            }

            LookupDisplayMode.GROUP_RESULTS -> showGroupLookupLoadingState(search, isCalculating)
            LookupDisplayMode.GROUPS -> Unit
        }
    }

    private fun showGroupLookupLoadingState(
        search: PendingLookupSearch,
        isCalculating: Boolean
    ) {
        val group = search.group ?: return
        val editor = editor ?: return
        val previousItems = getRelatedVisibleLookupItems(
            LookupDisplayMode.GROUP_RESULTS,
            search.searchText,
            group
        )
        val lookupItems = toLookupItemsWithLoading(
            previousItems,
            search.searchText,
            isCalculating
        )
        val existingLookup = lookup
        if (lookupMode == LookupDisplayMode.GROUP_RESULTS &&
            existingLookup != null &&
            existingLookup.isShown &&
            !existingLookup.isLookupDisposed
        ) {
            lookupManager.updateSuggestionLookup(
                existingLookup,
                lookupItems,
                search.searchText,
                isCalculating,
                matcherPrefix = getLookupPrefix(editor)
            )
        } else {
            showSuggestionLookup(lookupItems, search.searchText)
        }
        rememberVisibleLookupItems(
            LookupDisplayMode.GROUP_RESULTS,
            search.searchText,
            group,
            lookupItems
        )
    }

    private fun getRelatedVisibleLookupItems(
        mode: LookupDisplayMode,
        searchText: String,
        group: LookupGroupItem?
    ): List<LookupItem> {
        val cachedSearchText = lastVisibleLookupItemsSearchText ?: return emptyList()
        if (lastVisibleLookupItemsMode != mode || lastVisibleLookupItemsGroup != group) {
            return emptyList()
        }
        if (!areRelatedLookupSearches(cachedSearchText, searchText)) {
            return emptyList()
        }
        return lastVisibleLookupItems
    }

    private fun rememberVisibleLookupItems(
        mode: LookupDisplayMode,
        searchText: String,
        group: LookupGroupItem?,
        lookupItems: List<LookupItem>
    ) {
        val reusableItems = lookupItems
            .filterNot { it is LoadingLookupItem || it is StatusLookupItem }
            .take(PromptTextFieldConstants.MAX_SEARCH_RESULTS)
        if (reusableItems.isEmpty()) {
            if (!lookupSearchLoading) {
                clearVisibleLookupItems()
            }
            return
        }
        lastVisibleLookupItems = reusableItems
        lastVisibleLookupItemsSearchText = searchText
        lastVisibleLookupItemsMode = mode
        lastVisibleLookupItemsGroup = group
    }

    private fun clearVisibleLookupItems() {
        lastVisibleLookupItems = emptyList()
        lastVisibleLookupItemsSearchText = null
        lastVisibleLookupItemsMode = null
        lastVisibleLookupItemsGroup = null
    }

    private fun areRelatedLookupSearches(
        previousSearchText: String,
        currentSearchText: String
    ): Boolean {
        return currentSearchText.startsWith(previousSearchText, ignoreCase = true) ||
                previousSearchText.startsWith(currentSearchText, ignoreCase = true)
    }

    private fun addLookupCleanupListener(lookup: LookupImpl) {
        lookup.addLookupListener(object : LookupListener {
            override fun lookupCanceled(event: LookupEvent) {
                if (lookupMode == LookupDisplayMode.GLOBAL_SEARCH) {
                    clearVisibleLookupItems()
                    lookupMode = null
                    lookupSearchText = null
                }
            }

            override fun itemSelected(event: LookupEvent) {
                clearVisibleLookupItems()
                lookupMode = null
                lookupSearchText = null
            }
        })
    }

    private fun hideLookupIfShown() {
        lookup?.let { existingLookup ->
            if (!existingLookup.isLookupDisposed && existingLookup.isShown) {
                runInEdt { existingLookup.hide() }
            }
        }
        lookupMode = null
        lookupSearchText = null
        clearVisibleLookupItems()
    }

    private fun runGuardedLookupDocumentChange(
        documentChange: () -> Unit
    ) {
        val currentLookup = lookup
        if (currentLookup != null &&
            currentLookup.isShown &&
            !currentLookup.isLookupDisposed &&
            isCaretInsideAtLookupToken(currentLookup.editor)
        ) {
            currentLookup.performGuardedChange {
                documentChange()
            }
            return
        }

        documentChange()
    }

    private fun isCaretInsideAtLookupToken(editor: Editor): Boolean {
        return AtLookupToken.from(editor) != null
    }

    private fun getLookupPrefix(editor: Editor): String {
        return AtLookupToken.from(editor)?.prefix.orEmpty()
    }

    private suspend fun updateLookupWithGroups() {
        activeLookupGroup = null
        searchManager.clearSearchResultsCache()
        clearVisibleLookupItems()
        val lookupItems = searchManager.getDefaultGroups()
            .map { it.createLookupElement() }
            .toTypedArray()

        withContext(Dispatchers.Main) {
            editor?.let { editor ->
                lookup?.let { existingLookup ->
                    if (existingLookup.isShown && !existingLookup.isLookupDisposed) {
                        existingLookup.hide()
                    }
                }

                lookup = lookupManager.showGroupLookup(
                    editor = editor,
                    lookupElements = lookupItems,
                    onGroupSelected = { group -> handleGroupSelected(group) },
                    onWebActionSelected = { webAction -> onLookupAdded(webAction) },
                    onCodeAnalyzeSelected = { codeAnalyzeAction -> onLookupAdded(codeAnalyzeAction) }
                )
                lookupMode = LookupDisplayMode.GROUPS
                lookupSearchText = null
            }
        }
    }

    private suspend fun updateLookupWithSearchResults(
        searchText: String,
        optimisticResults: List<LookupActionItem> = emptyList(),
        generation: Long? = null
    ) {
        val instantResults = searchManager.performInstantSearch(searchText)
        val initialResults =
            searchManager.mergeResults(optimisticResults, instantResults, searchText)
        withContext(Dispatchers.Main) {
            showGlobalSearchResults(
                initialResults,
                searchText,
                isCalculating = isLookupSearchLoadingVisible(generation),
                generation = generation,
                showEmptyStatus = false
            )
        }

        delay(PromptTextFieldConstants.SEARCH_DELAY_MS.milliseconds)
        val fileResults = searchManager.performFileSearch(searchText)
        val earlyResults = searchManager.mergeResults(fileResults, initialResults, searchText)
        withContext(Dispatchers.Main) {
            showGlobalSearchResults(
                earlyResults,
                searchText,
                isCalculating = isLookupSearchLoadingVisible(generation),
                generation = generation,
                showEmptyStatus = false
            )
        }

        val deferredHeavyResults = searchManager.performDeferredHeavySearch(searchText)
        val allResults =
            searchManager.mergeResults(earlyResults, deferredHeavyResults, searchText)
        withContext(Dispatchers.Main) {
            showGlobalSearchResults(
                allResults,
                searchText,
                isCalculating = isLookupSearchLoadingVisible(generation),
                generation = generation
            )
        }
    }

    private suspend fun updateLookupWithGroupResults(
        group: LookupGroupItem,
        searchText: String,
        generation: Long? = null
    ) {
        val suggestions = group.getLookupItems(searchText)
        val previousItems = getRelatedVisibleLookupItems(
            LookupDisplayMode.GROUP_RESULTS,
            searchText,
            group
        )
        val lookupItems = toLookupItemsWithLoading(
            suggestions,
            searchText,
            isLookupSearchLoadingVisible(generation),
            previousItems
        )

        withContext(Dispatchers.Main) {
            if (generation != null && generation != lookupSearchLoadingGeneration) {
                return@withContext
            }
            val editor = editor ?: return@withContext
            val currentSearchText = runReadActionBlocking {
                searchManager.getSearchTextAfterAt(
                    editor.document.text,
                    editor.caretModel.offset
                )
            } ?: return@withContext
            if (activeLookupGroup != group ||
                getEffectiveGroupSearchText(group, currentSearchText) != searchText
            ) {
                return@withContext
            }

            val existingLookup = lookup
            if (lookupMode == LookupDisplayMode.GROUP_RESULTS &&
                existingLookup != null &&
                existingLookup.isShown &&
                !existingLookup.isLookupDisposed
            ) {
                lookupManager.updateSuggestionLookup(
                    existingLookup,
                    lookupItems,
                    searchText,
                    isCalculating = isLookupSearchLoadingVisible(generation),
                    matcherPrefix = getLookupPrefix(editor)
                )
                rememberVisibleLookupItems(
                    LookupDisplayMode.GROUP_RESULTS,
                    searchText,
                    group,
                    lookupItems
                )
                return@withContext
            }

            showSuggestionLookup(lookupItems, searchText)
        }
    }

    private fun adjustHeight(editor: EditorEx) {
        val contentHeight =
            editor.contentComponent.preferredSize.height + PromptTextFieldConstants.HEIGHT_PADDING
        val minimumHeight = calculateMinimumHeight(editor)

        val toolWindow = project.service<ToolWindowManager>().getToolWindow("ProxyAI")
        val maxHeight = if (toolWindow == null || !toolWindow.component.isAncestorOf(this)) {
            JBUI.scale(600)
        } else {
            JBUI.scale(getToolWindowHeight(toolWindow) / 2)
        }
        val newHeight = minOf(maxOf(contentHeight, minimumHeight), maxHeight)

        runInEdt {
            preferredSize = Dimension(width, newHeight)
            editor.setVerticalScrollbarVisible(contentHeight > maxHeight)
            parent?.revalidate()
        }
    }

    private fun calculateMinimumHeight(editor: EditorEx): Int {
        val verticalPadding = JBUI.scale(PromptTextFieldConstants.BORDER_PADDING * 2)
        return editor.lineHeight * PromptTextFieldConstants.MIN_VISIBLE_LINES + verticalPadding
    }

    private fun getToolWindowHeight(toolWindow: ToolWindow): Int {
        val h = toolWindow.component.visibleRect.height
        return if (h > 0) h else PromptTextFieldConstants.DEFAULT_TOOL_WINDOW_HEIGHT
    }

    companion object {
        private val logger = thisLogger()
        private val PROMPT_FIELD_KEY: Key<PromptTextField> =
            Key.create("codegpt.promptTextField.instance")

        private var editorInputHandlersInstalled = false

        internal fun toSearchLookupItems(
            results: List<LookupActionItem>,
            searchText: String,
            isCalculating: Boolean,
            previousVisibleLookupItems: List<LookupItem> = emptyList(),
            showEmptyStatus: Boolean = true
        ): List<LookupItem> {
            return toLookupItemsWithLoading(
                results,
                searchText,
                isCalculating,
                previousVisibleLookupItems,
                showEmptyStatus
            )
        }

        internal fun toLookupItemsWithLoading(
            results: List<LookupItem>,
            searchText: String,
            isCalculating: Boolean,
            previousVisibleLookupItems: List<LookupItem> = emptyList(),
            showEmptyStatus: Boolean = true
        ): List<LookupItem> {
            val baseItems = when {
                results.isNotEmpty() -> results
                isCalculating -> previousVisibleLookupItems
                !showEmptyStatus -> emptyList()
                else -> listOf(
                    StatusLookupItem(
                        displayName = PromptTextFieldLookupManager.EMPTY_RESULTS_TEXT,
                        lookupString = searchText
                    )
                )
            }

            return if (isCalculating) {
                baseItems.filterNot { it is LoadingLookupItem || it is StatusLookupItem } +
                        LoadingLookupItem(searchText)
            } else {
                baseItems
            }
        }

        private fun installEditorInputHandlers() {
            if (editorInputHandlersInstalled) return
            synchronized(PromptTextField::class.java) {
                if (editorInputHandlersInstalled) return
                val manager = EditorActionManager.getInstance()
                installTypedHandler()
                installGuardedEditorActionHandler(manager, IdeActions.ACTION_EDITOR_BACKSPACE)
                installGuardedEditorActionHandler(manager, IdeActions.ACTION_EDITOR_DELETE)
                installPasteHandler(manager)
                editorInputHandlersInstalled = true
            }
        }

        private fun installTypedHandler() {
            val typedAction = TypedAction.getInstance()
            val existing = typedAction.rawHandler
            typedAction.setupRawHandler(object : TypedActionHandler {
                override fun execute(
                    editor: Editor,
                    charTyped: Char,
                    dataContext: DataContext
                ) {
                    val field = editor.document.getUserData(PROMPT_FIELD_KEY)
                    if (field == null) {
                        existing.execute(editor, charTyped, dataContext)
                        return
                    }

                    field.runGuardedLookupDocumentChange {
                        existing.execute(editor, charTyped, dataContext)
                    }
                }
            })
        }

        private fun installGuardedEditorActionHandler(
            manager: EditorActionManager,
            actionId: String,
        ) {
            val existing = manager.getActionHandler(actionId)
            manager.setActionHandler(
                actionId,
                object : EditorActionHandler() {
                    override fun doExecute(
                        editor: Editor,
                        caret: Caret?,
                        dataContext: DataContext
                    ) {
                        val field = editor.document.getUserData(PROMPT_FIELD_KEY)
                        if (field == null) {
                            existing.execute(editor, caret, dataContext)
                            return
                        }

                        field.runGuardedLookupDocumentChange {
                            existing.execute(editor, caret, dataContext)
                        }
                    }
                })
        }

        private fun installPasteHandler(manager: EditorActionManager) {
            val existing = manager.getActionHandler(IdeActions.ACTION_EDITOR_PASTE)
            manager.setActionHandler(
                IdeActions.ACTION_EDITOR_PASTE,
                object : EditorActionHandler() {
                    override fun doExecute(
                        editor: Editor,
                        caret: Caret?,
                        dataContext: DataContext
                    ) {
                        val field = editor.document.getUserData(PROMPT_FIELD_KEY)
                        if (field != null) {
                            val pasted = try {
                                CopyPasteManager.getInstance()
                                    .getContents(DataFlavor.stringFlavor) as? String
                            } catch (_: Exception) {
                                null
                            }
                            if (!pasted.isNullOrEmpty()) {
                                field.runGuardedLookupDocumentChange {
                                    field.insertPlaceholderFor(pasted)
                                }
                                return
                            }
                        }

                        existing.execute(editor, caret, dataContext)
                    }
                })
        }
    }
}
