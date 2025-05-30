package ee.carlrobert.codegpt.ui.textarea

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.components.service
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
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTKeys.IS_PROMPT_TEXT_FIELD_DOCUMENT
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.*
import ee.carlrobert.codegpt.ui.textarea.lookup.action.FolderActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.WebActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.files.FileActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.action.git.GitCommitActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.group.*
import ee.carlrobert.codegpt.ui.textarea.popup.LookupListCellRenderer
import ee.carlrobert.codegpt.ui.textarea.popup.LookupListModel
import ee.carlrobert.codegpt.util.coroutines.runCatchingCancellable
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import kotlin.math.min

class PromptTextField(
    private val project: Project,
    private val tagManager: TagManager,
    private val onTextChanged: (String) -> Unit,
    private val onBackSpace: () -> Unit,
    private val onLookupAdded: (LookupActionItem) -> Unit,
    private val onSubmit: (String) -> Unit,
) : EditorTextField(project, FileTypes.PLAIN_TEXT), Disposable {

    private val coroutineScope = CoroutineScope(Dispatchers.EDT + SupervisorJob())
    private var showSuggestionsJob: Job? = null
    private var showLoadingDelayedJob: Job? = null
    private var searchJob: Job? = null

    val dispatcherId: UUID = UUID.randomUUID()
    private var currentPopup: JBPopup? = null
    private var currentPopupPanel: PopupPanel? = null
    private var currentParentGroup: LookupGroupItem? = null
    private var currentItems: List<LookupItem> = emptyList()

    init {
        isOneLineMode = false
        IS_PROMPT_TEXT_FIELD_DOCUMENT.set(document, true)
        setPlaceholder(CodeGPTBundle.get("toolwindow.chat.textArea.emptyText"))
    }

    override fun onEditorAdded(editor: Editor) {
        IdeEventQueue.getInstance().addDispatcher(
            PromptTextFieldEventDispatcher(dispatcherId, onBackSpace) {
                if (currentPopup?.isVisible == true) {
                    return@PromptTextFieldEventDispatcher
                }

                onSubmit(text)
                it.consume()
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
        val lookupItems = listOf(
            FilesGroupItem(project, tagManager),
            FoldersGroupItem(project, tagManager),
            GitGroupItem(project),
            PersonasGroupItem(tagManager),
            DocsGroupItem(tagManager),
            MCPGroupItem(),
            WebActionItem(tagManager)
        ).filter { it.enabled }

        withContext(Dispatchers.EDT) {
            editor?.let { showPopupLookup(it, lookupItems) }
        }
    }

    private fun showPopupLookup(editor: Editor, items: List<LookupItem>) {
        currentPopup?.cancel()
        currentItems = items

        val popupPanel = createPopupPanel(items, null)
        currentPopupPanel = popupPanel

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupPanel, popupPanel.itemsList)
            .setFocusable(true)
            .setRequestFocus(true)
            .setResizable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .createPopup()

        val relativePoint = calculateOptimalPopupPosition(editor, popupPanel)

        currentPopup = popup
        popup.show(relativePoint)

        runInEdt {
            popupPanel.itemsList.requestFocus()
        }
    }

    private fun createPopupPanel(items: List<LookupItem>, parentGroup: LookupGroupItem?): PopupPanel {
        return PopupPanel(items, parentGroup)
    }

    private inner class PopupPanel(
        items: List<LookupItem>,
        val parentGroup: LookupGroupItem?
    ) : JBPanel<PopupPanel>(BorderLayout()) {

        private val listModel = LookupListModel(items)
        val itemsList = JBList(listModel)
        private var currentParentGroup: LookupGroupItem? = parentGroup

        init {
            setupList()
            setupLayout()
            updateSearchFromEditor()
        }

        fun updateItems(newItems: List<LookupItem>, newParentGroup: LookupGroupItem?) {
            currentParentGroup = newParentGroup
            updateListItems(newItems)
        }

        private fun setupList() {
            itemsList.apply {
                cellRenderer = LookupListCellRenderer()
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                if (model.size > 0) selectedIndex = 0

                addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        when (e.keyCode) {
                            KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                                selectCurrentItem()
                                e.consume()
                            }

                            KeyEvent.VK_ESCAPE -> {
                                currentPopup?.cancel()
                                e.consume()
                            }

                            KeyEvent.VK_UP -> {
                                if (selectedIndex > 0) {
                                    selectedIndex -= 1
                                } else if (selectedIndex == 0 && model.size > 0) {
                                    selectedIndex = model.size - 1
                                }
                                e.consume()
                            }

                            KeyEvent.VK_DOWN -> {
                                if (selectedIndex < model.size - 1) {
                                    selectedIndex += 1
                                } else if (selectedIndex == model.size - 1) {
                                    selectedIndex = 0
                                }
                                e.consume()
                            }

                            KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> {
                                redirectKeyToEditor(e)
                            }

                            else -> {
                                if (e.keyChar.isLetterOrDigit() || e.keyChar.isWhitespace() ||
                                    e.keyChar == '.' || e.keyChar == '/' || e.keyChar == '_' || e.keyChar == '-'
                                ) {
                                    redirectKeyToEditor(e)
                                }
                            }
                        }
                    }
                })

                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount == 2) {
                            selectCurrentItem()
                        }
                    }
                })
            }
        }

        private fun setupLayout() {
            border = JBUI.Borders.empty(5)
            add(JBScrollPane(itemsList), BorderLayout.CENTER)

            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                            if (itemsList.selectedIndex >= 0) {
                                selectCurrentItem()
                                e.consume()
                            }
                        }

                        KeyEvent.VK_ESCAPE -> {
                            currentPopup?.cancel()
                            e.consume()
                        }

                        else -> {
                            if (e.keyChar.isLetterOrDigit() || e.keyChar.isWhitespace() ||
                                e.keyChar == '.' || e.keyChar == '/' || e.keyChar == '_' || e.keyChar == '-'
                            ) {
                                redirectKeyToEditor(e)
                            }
                        }
                    }
                }
            })
        }

        private fun redirectKeyToEditor(e: KeyEvent) {
            editor?.let { editor ->
                runInEdt {
                    runUndoTransparentWriteAction {
                        when (e.keyCode) {
                            KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> {
                                val offset = editor.caretModel.offset
                                if (offset > 0) {
                                    editor.document.deleteString(offset - 1, offset)
                                }
                            }

                            else -> {
                                if (e.keyChar != KeyEvent.CHAR_UNDEFINED && e.keyChar.isDefined()) {
                                    val offset = editor.caretModel.offset
                                    editor.document.insertString(offset, e.keyChar.toString())
                                    editor.caretModel.moveToOffset(offset + 1)
                                }
                            }
                        }
                    }
                }
                e.consume()
            }
        }

        override fun getPreferredSize(): Dimension {
            val maxWidth = 450
            val minWidth = 300
            val minHeight = 150
            val maxHeight = 400

            val listSize = itemsList.preferredSize
            val contentWidth = listSize.width + 10
            val contentHeight = listSize.height + 10

            val width = minOf(maxOf(contentWidth, minWidth), maxWidth)
            val height = minOf(maxOf(contentHeight, minHeight), maxHeight)

            return Dimension(width, height)
        }

        fun updateSearchFromEditor() {
            editor?.let { editor ->
                when (val result = getSearchTextAfterAt(editor)) {
                    is SearchTextResult.Found -> {
                        updateFilter(result.query)
                    }

                    is SearchTextResult.Cancelled -> {
                        currentPopup?.cancel()
                    }

                    is SearchTextResult.None -> {
                        currentPopup?.cancel()
                    }
                }
            }
        }

        fun updateFilter(searchText: String) {
            searchJob?.cancel()
            if (searchText.isEmpty()) {
                updateListItems(this@PromptTextField.currentItems)
                return
            }

            searchJob = coroutineScope.launch {
                val currentParentGroup = currentParentGroup
                if (currentParentGroup != null && searchText.length >= 2) {
                    showLoadingState()

                    runCatchingCancellable {
                        val items = if (currentParentGroup is DynamicLookupGroupItem) {
                            currentParentGroup.updateLookupItems(searchText)
                        } else {
                            currentParentGroup.getLookupItems(searchText)
                        }
                            .distinctBy { it.displayName }

                        updateListItems(items)
                    }
                        .onFailure {
                            updateListItems(emptyList())
                        }
                } else if (searchText.length >= 2) {
                    showLoadingState()
                    runCatchingCancellable {
                        val filteredItems = this@PromptTextField.currentItems
                            .flatMap { item ->
                                when (item) {
                                    is DynamicLookupGroupItem -> item.getLookupItems(searchText).take(5)
                                    is LookupGroupItem -> item.getLookupItems(searchText).take(5)
                                    else -> listOf(item)
                                }
                            }
                            .distinctBy { it.displayName }
                            .filter { it.displayName.contains(searchText, ignoreCase = true) }
                        yield()
                        updateListItems(filteredItems)
                    }
                        .onFailure {
                            yield()
                            updateListItems(emptyList())
                        }
                } else {
                    val filteredItems = this@PromptTextField.currentItems.filter {
                        it.displayName.contains(searchText, ignoreCase = true)
                    }
                    updateListItems(filteredItems)
                }
            }
        }

        private fun showLoadingState() {
            showLoadingDelayedJob?.cancel()
            showLoadingDelayedJob = coroutineScope.launch {
                delay(SHOW_LOADING_DELAY)
                val loadingItems = listOf(LoadingLookupItem())
                val model = LookupListModel(loadingItems)
                itemsList.model = model
                itemsList.selectedIndex = -1
            }
        }

        private fun updateListItems(items: List<LookupItem>) {
            showLoadingDelayedJob?.cancel()
            runInEdt {
                val model = LookupListModel(items)
                itemsList.model = model
                if (model.size > 0) {
                    itemsList.selectedIndex = 0
                }
            }
        }

        private fun selectCurrentItem() {
            val selectedIndex = itemsList.selectedIndex
            if (selectedIndex >= 0) {
                val selectedItem = (itemsList.model as LookupListModel).getElementAt(selectedIndex)

                if (selectedItem is LoadingLookupItem) {
                    return
                }

                editor?.let { handleItemSelection(it, selectedItem) }
            }
        }
    }

    private sealed class SearchTextResult {
        data class Found(val query: String) : SearchTextResult()
        data object Cancelled : SearchTextResult()
        data object None : SearchTextResult()
    }

    private fun getSearchTextAfterAt(editor: Editor): SearchTextResult {
        val text = editor.document.text
        val caretOffset = editor.caretModel.offset
        val atPos = text.lastIndexOf('@', caretOffset)

        if (atPos !in 0 until caretOffset) {
            return SearchTextResult.None
        }
        val endIndex = min(caretOffset + 1, text.length)
        val substring = text.substring(atPos + 1, endIndex)

        return if (substring.contains("  ") || substring.contains("\n")) {
            SearchTextResult.Cancelled
        } else {
            SearchTextResult.Found(substring)
        }
    }

    private fun handleItemSelection(editor: Editor, item: LookupItem) {
        when (item) {
            is WebActionItem -> {
                val offset = editor.caretModel.offset
                val start = findAtSymbolPosition(editor)
                if (start >= 0) {
                    runUndoTransparentWriteAction {
                        editor.document.deleteString(start, offset)
                    }
                }
                onLookupAdded(item)
                currentPopup?.cancel()
            }

            is LookupGroupItem -> {
                showSuggestionsJob?.cancel()
                showSuggestionsJob = coroutineScope.launch {
                    val suggestions = item.getLookupItems()
                    if (suggestions.isEmpty()) {
                        return@launch
                    }

                    runInEdt {
                        updatePopupContent(suggestions, item)
                    }
                }
            }

            is LookupActionItem -> {
                replaceAtSymbol(editor, item)
                onLookupAdded(item)
                currentPopup?.cancel()
            }
        }
    }

    private fun updatePopupContent(items: List<LookupItem>, parentGroup: LookupGroupItem) {
        currentPopup?.let { popup ->
            if (popup.isVisible) {
                currentPopupPanel?.let { panel ->
                    currentParentGroup = parentGroup
                    currentItems = items
                    panel.updateItems(items, parentGroup)
                    panel.itemsList.requestFocusInWindow()
                }
            }
        }
    }

    private fun findAtSymbolPosition(editor: Editor): Int {
        val atPos = editor.document.text.lastIndexOf('@')
        return if (atPos >= 0) atPos else -1
    }

    private suspend fun showGroupSuggestions(group: LookupGroupItem) {
        val suggestions = group.getLookupItems()
        if (suggestions.isEmpty()) {
            return
        }

        withContext(Dispatchers.EDT) {
            updatePopupContent(suggestions, group)
        }
    }

    private fun showSuggestionPopup(
        items: List<LookupItem>,
        parentGroup: LookupGroupItem
    ) {
        editor?.let { editor ->
            currentPopup?.cancel()
            currentParentGroup = parentGroup

            val popupPanel = createPopupPanel(items, parentGroup)
            currentPopupPanel = popupPanel

            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(popupPanel, popupPanel.itemsList)
                .setFocusable(true)
                .setRequestFocus(true)
                .setResizable(true)
                .setCancelOnClickOutside(true)
                .setCancelOnWindowDeactivation(true)
                .createPopup()

            val relativePoint = calculateOptimalPopupPosition(editor, popupPanel)

            currentPopup = popup
            popup.show(relativePoint)

            runInEdt {
                popupPanel.itemsList.requestFocus()
            }
        }
    }

    private fun calculateOptimalPopupPosition(editor: Editor, popupPanel: PopupPanel): RelativePoint {
        val caretPosition = editor.caretModel.visualPosition
        val caretPoint = editor.visualPositionToXY(caretPosition)
        val editorComponent = editor.contentComponent

        val caretLocationOnScreen = Point(caretPoint.x, caretPoint.y)
        SwingUtilities.convertPointToScreen(caretLocationOnScreen, editorComponent)

        val popupSize = popupPanel.preferredSize
        val lineHeight = editor.lineHeight
        val margin = JBUI.scale(8)

        val screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds

        val spaceBelow = screenBounds.height - caretLocationOnScreen.y - lineHeight
        val spaceAbove = caretLocationOnScreen.y

        val showAbove = popupSize.height + margin in (spaceBelow + 1)..spaceAbove

        val finalY = if (showAbove) {
            caretLocationOnScreen.y - popupSize.height - margin
        } else {
            caretLocationOnScreen.y + lineHeight + margin
        }

        val screenPoint = Point(caretLocationOnScreen.x, finalY)

        val editorLocationOnScreen = Point(0, 0)
        SwingUtilities.convertPointToScreen(editorLocationOnScreen, editorComponent)

        val relativeX = screenPoint.x - editorLocationOnScreen.x
        val relativeY = screenPoint.y - editorLocationOnScreen.y

        return RelativePoint(editorComponent, Point(relativeX, relativeY))
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
        searchJob?.cancel()
        showSuggestionsJob?.cancel()
        currentPopup?.cancel()
        currentPopupPanel = null
    }

    private fun setupDocumentListener(editor: EditorEx) {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                adjustHeight(editor)
                onTextChanged(event.document.text)

                if ("@" == event.newFragment.toString()) {
                    showSuggestionsJob?.cancel()
                    showSuggestionsJob = coroutineScope.launch {
                        showGroupLookup()
                    }
                } else {
                    currentPopup?.let { popup ->
                        if (popup.isVisible) {
                            currentPopupPanel?.updateSearchFromEditor()
                        }
                    }
                }
            }
        }, this)
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

    private companion object {
        /**
         * Delay in milliseconds before showing the loading indicator
         * to avoid flicker for quick operations.
         */
        const val SHOW_LOADING_DELAY = 150L
    }
}