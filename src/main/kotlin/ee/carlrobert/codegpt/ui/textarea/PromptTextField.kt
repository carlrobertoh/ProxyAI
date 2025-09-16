package ee.carlrobert.codegpt.ui.textarea

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTKeys.IS_PROMPT_TEXT_FIELD_DOCUMENT
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.lookup.DynamicLookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupGroupItem
import ee.carlrobert.codegpt.ui.dnd.FileDragAndDrop
import kotlinx.coroutines.*
import java.awt.Dimension
import java.util.*

class PromptTextField(
    private val project: Project,
    tagManager: TagManager,
    private val onTextChanged: (String) -> Unit,
    private val onBackSpace: () -> Unit,
    private val onLookupAdded: (LookupActionItem) -> Unit,
    private val onSubmit: (String) -> Unit,
    private val onFilesDropped: (List<VirtualFile>) -> Unit = {},
) : EditorTextField(project, FileTypes.PLAIN_TEXT), Disposable {

    companion object {
        private val logger = thisLogger()
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val lookupManager = PromptTextFieldLookupManager(project, onLookupAdded)
    private val searchManager = SearchManager(project, tagManager)

    private var showSuggestionsJob: Job? = null
    private var searchState = SearchState()
    private var lastSearchResults: List<LookupActionItem>? = null

    val dispatcherId: UUID = UUID.randomUUID()
    var lookup: LookupImpl? = null

    init {
        isOneLineMode = false
        IS_PROMPT_TEXT_FIELD_DOCUMENT.set(document, true)
        setPlaceholder(CodeGPTBundle.get("toolwindow.chat.textArea.emptyText"))
        
        putClientProperty(UIUtil.HIDE_EDITOR_FROM_DATA_CONTEXT_PROPERTY, true)
    }

    override fun onEditorAdded(editor: Editor) {
        IdeEventQueue.getInstance().addDispatcher(
            PromptTextFieldEventDispatcher(dispatcherId, onBackSpace, lookup) { event ->
                val shown = lookup?.let { it.isShown && !it.isLookupDisposed } == true
                if (shown) {
                    return@PromptTextFieldEventDispatcher
                }

                onSubmit(text)
                event.consume()
            },
            this
        )
        val highlightTarget = (this.parent as? javax.swing.JComponent) ?: this
        FileDragAndDrop.install(editor.contentComponent as javax.swing.JComponent, highlightTarget) { onFilesDropped(it) }
    }

    fun clear() {
        runInEdt {
            text = ""
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
        val text = event.document.text
        val caretOffset = event.offset + event.newLength

        when {
            isAtSymbolTyped(event) -> handleAtSymbolTyped()
            else -> handleTextChange(text, caretOffset)
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
        val toolWindow = project.service<ToolWindowManager>().getToolWindow("ProxyAI")
        if (toolWindow == null || !toolWindow.component.isAncestorOf(this)) {
            return
        }
        
        val contentHeight =
            editor.contentComponent.preferredSize.height + PromptTextFieldConstants.HEIGHT_PADDING
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
            .getToolWindow("ProxyAI")?.component?.visibleRect?.height
            ?: PromptTextFieldConstants.DEFAULT_TOOL_WINDOW_HEIGHT
    }
}
