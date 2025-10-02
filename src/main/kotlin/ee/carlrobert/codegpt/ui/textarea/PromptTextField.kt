package ee.carlrobert.codegpt.ui.textarea

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTKeys.IS_PROMPT_TEXT_FIELD_DOCUMENT
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.ui.dnd.FileDragAndDrop
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupGroupItem
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

class PromptTextField(
    private val project: Project,
    tagManager: TagManager,
    private val onTextChanged: (String) -> Unit,
    private val onBackSpace: () -> Unit,
    private val onLookupAdded: (LookupActionItem) -> Unit,
    private val onSubmit: (String) -> Unit,
    private val onFilesDropped: (List<VirtualFile>) -> Unit = {},
    featureType: FeatureType? = null
) : EditorTextField(project, FileTypes.PLAIN_TEXT), Disposable {

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val lookupManager = PromptTextFieldLookupManager(project, onLookupAdded)
    private val searchManager = SearchManager(project, tagManager, featureType)

    private var mouseClickListener: MouseAdapter? = null
    private var mouseMotionListener: MouseMotionAdapter? = null

    private var showSuggestionsJob: Job? = null
    private var searchState = SearchState()
    private var lastSearchResults: List<LookupActionItem>? = null

    val dispatcherId: UUID = UUID.randomUUID()
    var lookup: LookupImpl? = null

    init {
        isOneLineMode = false
        IS_PROMPT_TEXT_FIELD_DOCUMENT.set(document, true)
        document.putUserData(PROMPT_FIELD_KEY, this)
        setPlaceholder(CodeGPTBundle.get("toolwindow.chat.textArea.emptyText"))

        putClientProperty(UIUtil.HIDE_EDITOR_FROM_DATA_CONTEXT_PROPERTY, true)
        installPasteHandler()
    }

    override fun onEditorAdded(editor: Editor) {
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
                lookup = lookupManager.showGroupLookup(
                    editor = editor,
                    lookupElements = lookupItems,
                    onGroupSelected = { group, selectedText ->
                        handleGroupSelected(group, selectedText)
                    },
                    onWebActionSelected = { webAction ->
                        onLookupAdded(webAction)
                    },
                    onCodeAnalyzeSelected = { codeAnalyzeAction ->
                        onLookupAdded(codeAnalyzeAction)
                    }
                )
            }
        }
    }

    private fun showGlobalSearchResults(
        results: List<LookupActionItem>,
        searchText: String
    ) {
        editor?.let { editor ->
            try {
                hideLookupIfShown()
                lookup = lookupManager.showSearchResultsLookup(editor, results, searchText)
            } catch (e: Exception) {
                logger.error("Error showing lookup: $e", e)
            }
        }
    }

    private fun handleGroupSelected(group: LookupGroupItem, searchText: String) {
        showSuggestionsJob?.cancel()
        showSuggestionsJob = coroutineScope.launch {
            showGroupSuggestions(group, searchText)
        }
    }

    private suspend fun showGroupSuggestions(group: LookupGroupItem, filterText: String = "") {
        val suggestions = group.getLookupItems()
        if (suggestions.isEmpty()) {
            return
        }

        val lookupElements = suggestions.map { it.createLookupElement() }.toTypedArray()

        withContext(Dispatchers.Main) {
            showSuggestionLookup(lookupElements, group, filterText)
        }
    }

    private fun showSuggestionLookup(
        lookupElements: Array<LookupElement>,
        parentGroup: LookupGroupItem,
        filterText: String = "",
    ) {
        editor?.let { editor ->
            searchState = searchState.copy(isInGroupLookupContext = true)

            lookup = lookupManager.showSuggestionLookup(
                editor = editor,
                lookupElements = lookupElements,
                parentGroup = parentGroup,
                onDynamicUpdate = { searchText ->
                    handleDynamicUpdate(parentGroup, lookupElements, searchText, filterText)
                },
                filterText = filterText
            )

            lookup?.addLookupListener(object : LookupListener {
                override fun lookupCanceled(event: LookupEvent) {
                    searchState = searchState.copy(isInGroupLookupContext = false)
                }
            })
        }
    }

    private fun handleDynamicUpdate(
        parentGroup: LookupGroupItem,
        lookupElements: Array<LookupElement>,
        searchText: String,
        filterText: String
    ) {
        showSuggestionsJob?.cancel()
        showSuggestionsJob = coroutineScope.launch {
            if (parentGroup is DynamicLookupGroupItem) {
                if (searchText.length >= PromptTextFieldConstants.MIN_DYNAMIC_SEARCH_LENGTH) {
                    parentGroup.updateLookupList(lookup!!, searchText)
                } else if (searchText.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showSuggestionLookup(lookupElements, parentGroup, filterText)
                    }
                }
            }
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
        editor.setBorder(
            JBUI.Borders.empty(
                PromptTextFieldConstants.BORDER_PADDING,
                PromptTextFieldConstants.BORDER_SIDE_PADDING
            )
        )
    }

    override fun dispose() {
        showSuggestionsJob?.cancel()
        lastSearchResults = null
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
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                adjustHeight(editor)
                onTextChanged(event.document.text)
                handleDocumentChange(event)
            }
        }, this)
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
            searchText != null && searchText.isEmpty() -> handleEmptySearch()
            !searchText.isNullOrEmpty() -> handleNonEmptySearch(searchText)
            searchText == null -> handleNoSearch()
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
                        lastSearchText = searchText
                    )

                    showSuggestionsJob?.cancel()
                    showSuggestionsJob = coroutineScope.launch {
                        delay(PromptTextFieldConstants.SEARCH_DELAY_MS)
                        updateLookupWithSearchResults(searchText)
                    }
                }
            }
        }
    }

    private fun handleNoSearch() {
        if (searchState.isInSearchContext) {
            searchState = SearchState()
            showSuggestionsJob?.cancel()
            hideLookupIfShown()
        }
    }

    private fun hideLookupIfShown() {
        lookup?.let { existingLookup ->
            if (!existingLookup.isLookupDisposed && existingLookup.isShown) {
                runInEdt { existingLookup.hide() }
            }
        }
    }

    private suspend fun updateLookupWithGroups() {
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
                    onGroupSelected = { group, currentSearchText ->
                        handleGroupSelected(
                            group,
                            currentSearchText
                        )
                    },
                    onWebActionSelected = { webAction -> onLookupAdded(webAction) },
                    onCodeAnalyzeSelected = { codeAnalyzeAction -> onLookupAdded(codeAnalyzeAction) },
                    searchText = ""
                )
            }
        }
    }

    private suspend fun updateLookupWithSearchResults(searchText: String) {
        val matchedResults = searchManager.performGlobalSearch(searchText)

        if (lastSearchResults != matchedResults) {
            lastSearchResults = matchedResults
            withContext(Dispatchers.Main) {
                showGlobalSearchResults(matchedResults, searchText)
            }
        }
    }

    private fun adjustHeight(editor: EditorEx) {
        val contentHeight =
            editor.contentComponent.preferredSize.height + PromptTextFieldConstants.HEIGHT_PADDING

        val toolWindow = project.service<ToolWindowManager>().getToolWindow("ProxyAI")
        val maxHeight = if (toolWindow == null || !toolWindow.component.isAncestorOf(this)) {
            JBUI.scale(600)
        } else {
            JBUI.scale(getToolWindowHeight(toolWindow) / 2)
        }
        val newHeight = minOf(contentHeight, maxHeight)

        runInEdt {
            preferredSize = Dimension(width, newHeight)
            editor.setVerticalScrollbarVisible(contentHeight > maxHeight)
            parent?.revalidate()
        }
    }

    private fun getToolWindowHeight(toolWindow: ToolWindow): Int {
        return toolWindow.component.visibleRect?.height
            ?: PromptTextFieldConstants.DEFAULT_TOOL_WINDOW_HEIGHT
    }

    companion object {
        private val logger = thisLogger()
        private val PROMPT_FIELD_KEY: Key<PromptTextField> =
            Key.create("codegpt.promptTextField.instance")

        private var pasteHandlerInstalled = false
        private var originalPasteHandler: EditorActionHandler? = null

        private fun installPasteHandler() {
            if (pasteHandlerInstalled) return
            synchronized(PromptTextField::class.java) {
                if (pasteHandlerInstalled) return
                val manager = EditorActionManager.getInstance()
                val existing = manager.getActionHandler(IdeActions.ACTION_EDITOR_PASTE)
                originalPasteHandler = existing
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
                                    field.insertPlaceholderFor(pasted)
                                    return
                                }
                            }
                            originalPasteHandler?.execute(editor, caret, dataContext)
                        }
                    })
                pasteHandlerInstalled = true
            }
        }
    }
}
