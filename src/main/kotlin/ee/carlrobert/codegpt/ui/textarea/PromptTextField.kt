package ee.carlrobert.codegpt.ui.textarea

import com.intellij.codeInsight.lookup.*
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.codeInsight.lookup.impl.PrefixChangeListener
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
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
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
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
    private val onSubmit: (String) -> Unit
) : EditorTextField(project, FileTypes.PLAIN_TEXT), Disposable {

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var showSuggestionsJob: Job? = null

    val dispatcherId: UUID = UUID.randomUUID()

    init {
        isOneLineMode = false
        background = UIUtil.getTextFieldBackground()
        IS_PROMPT_TEXT_FIELD_DOCUMENT.set(document, true)
        setPlaceholder(CodeGPTBundle.get("toolwindow.chat.textArea.emptyText"))
    }

    override fun onEditorAdded(editor: Editor) {
        IdeEventQueue.getInstance().addDispatcher(
            PromptTextFieldEventDispatcher(dispatcherId, onBackSpace) {
                onSubmit(text)
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
        )
            .filter { it.enabled }
            .map { it.createLookupElement() }
            .toTypedArray()

        withContext(Dispatchers.Main) {
            editor?.let {
                showGroupLookup(it, lookupItems)
            }
        }
    }

    private fun showGroupLookup(editor: Editor, lookupElements: Array<LookupElement>) {
        val lookup = project.service<LookupManager>().createLookup(
            editor,
            lookupElements,
            "",
            LookupArranger.DefaultArranger()
        ) as LookupImpl

        lookup.addLookupListener(object : LookupListener {
            override fun itemSelected(event: LookupEvent) {
                val lookupString = event.item?.lookupString ?: return
                val suggestion =
                    event.item?.getUserData(LookupItem.KEY) ?: return
                val offset = editor.caretModel.offset
                val start = offset - lookupString.length
                if (start >= 0) {
                    runUndoTransparentWriteAction {
                        editor.document.deleteString(start, offset)
                    }
                }

                if (suggestion is WebActionItem) {
                    onLookupAdded(suggestion)
                }

                if (suggestion !is LookupGroupItem) return

                showSuggestionsJob?.cancel()
                showSuggestionsJob = coroutineScope.launch {
                    showGroupSuggestions(suggestion)
                }
            }
        })
        lookup.refreshUi(false, true)
        lookup.showLookup()
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

        val lookupElements = suggestions.map { it.createLookupElement() }.toTypedArray()

        withContext(Dispatchers.Main) {
            showSuggestionLookup(lookupElements, group)
        }
    }

    private fun createLookup(
        editor: Editor,
        lookupElements: Array<LookupElement>,
        searchText: String
    ) = LookupManager.getInstance(project).createLookup(
        editor,
        lookupElements,
        searchText,
        LookupArranger.DefaultArranger()
    ) as LookupImpl

    private fun showSuggestionLookup(
        lookupElements: Array<LookupElement>,
        parentGroup: LookupGroupItem,
        filterText: String = "",
    ) {
        editor?.let {
            val lookup = createLookup(it, lookupElements, filterText)
            lookup.addLookupListener(object : LookupListener {
                override fun itemSelected(event: LookupEvent) {
                    val lookupItem = event.item?.getUserData(LookupItem.KEY) ?: return
                    if (lookupItem !is LookupActionItem) return

                    replaceAtSymbol(it, lookupItem)
                    onLookupAdded(lookupItem)
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

            lookup.addPrefixChangeListener(object : PrefixChangeListener {
                override fun afterAppend(c: Char) {
                    showSuggestionsJob?.cancel()
                    showSuggestionsJob = coroutineScope.launch {
                        if (parentGroup is DynamicLookupGroupItem) {
                            val searchText = getSearchText()
                            if (searchText.length == 2) {
                                parentGroup.updateLookupList(lookup, searchText)
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

            lookup.refreshUi(false, true)
            lookup.showLookup()
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
}