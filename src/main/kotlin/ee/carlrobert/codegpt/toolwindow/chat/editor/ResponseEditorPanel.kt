package ee.carlrobert.codegpt.toolwindow.chat.editor

import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.intellij.util.application
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTKeys.CODEGPT_USER_DETAILS
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.completions.AutoApplyParameters
import ee.carlrobert.codegpt.completions.CompletionClientProvider
import ee.carlrobert.codegpt.completions.CompletionRequestService
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType.PROXYAI
import ee.carlrobert.codegpt.toolwindow.chat.editor.diff.DiffSyncManager
import ee.carlrobert.codegpt.toolwindow.chat.editor.factory.ComponentFactory
import ee.carlrobert.codegpt.toolwindow.chat.editor.factory.ComponentFactory.EXPANDED_KEY
import ee.carlrobert.codegpt.toolwindow.chat.editor.factory.ComponentFactory.MIN_LINES_FOR_EXPAND
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DefaultHeaderPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DiffHeaderPanel
import ee.carlrobert.codegpt.toolwindow.chat.editor.state.EditorState
import ee.carlrobert.codegpt.toolwindow.chat.editor.state.EditorStateManager
import ee.carlrobert.codegpt.toolwindow.chat.parser.ReplaceWaiting
import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchReplace
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment
import ee.carlrobert.codegpt.util.ApplicationUtil
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.llm.client.codegpt.request.AutoApplyRequest

class ResponseEditorPanel(
    private val project: Project,
    item: Segment,
    readOnly: Boolean,
    disposableParent: Disposable,
) : BorderLayoutPanel(), Disposable {

    companion object {
        val RESPONSE_EDITOR_DIFF_VIEWER_KEY =
            Key.create<UnifiedDiffViewer?>("proxyai.responseEditorDiffViewer")
        val RESPONSE_EDITOR_DIFF_VIEWER_VALUE_PAIR_KEY =
            Key.create<Pair<String, String>>("proxyai.responseEditorDiffViewerValuePair")
        val RESPONSE_EDITOR_STATE_KEY = Key.create<EditorState>("proxyai.responseEditorState")
    }

    private val logger = thisLogger()
    private val stateManager = EditorStateManager(project)
    private var searchReplaceHandler: SearchReplaceHandler

    init {
        border = JBUI.Borders.empty(8, 0)
        isOpaque = false

        val state = stateManager.createFromSegment(item, readOnly)
        val editor = state.editor
        configureEditor(editor)
        searchReplaceHandler = SearchReplaceHandler(stateManager) { oldEditor, newEditor ->
            replaceEditor(oldEditor, newEditor)
        }

        addToCenter(editor.component)
        updateEditorUI()

        Disposer.register(disposableParent, this)
    }

    fun handleSearchReplace(item: SearchReplace) {
        searchReplaceHandler.handleSearchReplace(item)
    }

    fun handleReplace(item: ReplaceWaiting) {
        searchReplaceHandler.handleReplace(item)
    }

    fun getEditor(): EditorEx? {
        return stateManager.getCurrentState()?.editor
    }

    fun replaceEditor(oldEditor: EditorEx, newEditor: EditorEx) {
        runInEdt {
            val expanded = oldEditor.getUserData(EXPANDED_KEY) == true
            EXPANDED_KEY.set(newEditor, expanded)

            removeAll()

            configureEditor(newEditor)
            addToCenter(newEditor.component)

            ComponentFactory.updateEditorPreferredSize(newEditor, expanded)
            updateEditorUI()

            revalidate()
            repaint()
        }
    }

    fun replaceEditorWithSegment(segment: Segment) {
        val oldEditor = stateManager.getCurrentState()?.editor ?: return
        val newState = stateManager.createFromSegment(segment)
        replaceEditor(oldEditor, newState.editor)
    }

    fun applyCodeAsync(
        content: String,
        virtualFile: VirtualFile,
        editor: EditorEx,
        headerPanel: DefaultHeaderPanel? = null
    ) {
        var selectedService =
            ModelSelectionService.getInstance().getServiceForFeature(FeatureType.AUTO_APPLY);

        if (selectedService == PROXYAI) {
            val panel = headerPanel ?: (editor.permanentHeaderComponent as? DefaultHeaderPanel)
            panel?.setLoading()
            CompletionProgressNotifier.update(project, true)
            application.executeOnPooledThread {
                val pricingPlan =
                    CODEGPT_USER_DETAILS[ApplicationUtil.findCurrentProject()]?.pricingPlan
                val model = service<ModelSelectionService>()
                    .getModelForFeature(FeatureType.AUTO_APPLY, pricingPlan)
                val originalCode = EditorUtil.getFileContent(virtualFile)
                try {
                    val request = AutoApplyRequest(model, originalCode, content)
                    val response = CompletionClientProvider.getCodeGPTClient().applyChanges(request)
                    stateManager.transitionToDiffState(originalCode, response.mergedCode, virtualFile)
                } catch (e: Exception) {
                    logger.error("Failed to apply changes", e)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            panel?.handleDone()
                        }
                    }
                } finally {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            CompletionProgressNotifier.update(project, false)
                        }
                    }
                }
            }
            return
        }

        val eventSource = CompletionRequestService.getInstance().autoApplyAsync(
            AutoApplyParameters(content, virtualFile),
            AutoApplyListener(project, stateManager, virtualFile, content) { oldEditor, newEditor ->
                val responseEditorPanel = editor.component.parent as? ResponseEditorPanel
                    ?: throw IllegalStateException("Expected parent to be ResponseEditorPanel")
                responseEditorPanel.replaceEditor(oldEditor, newEditor)
            })

        val panel = headerPanel ?: (editor.permanentHeaderComponent as? DefaultHeaderPanel)
        panel?.setLoading(eventSource)
    }

    internal fun createReplaceWaitingSegment(
        searchContent: String,
        replaceContent: String,
        virtualFile: VirtualFile
    ): ReplaceWaiting {
        return ReplaceWaiting(
            search = searchContent,
            replace = replaceContent,
            language = virtualFile.extension ?: "text",
            filePath = virtualFile.path
        )
    }

    fun createDiffEditorForDirectApply(
        searchContent: String,
        replaceContent: String,
        virtualFile: VirtualFile
    ) {
        try {
            val segment = createReplaceWaitingSegment(searchContent, "", virtualFile)

            val oldEditor = stateManager.getCurrentState()?.editor
            if (oldEditor == null) {
                logger.warn("No current editor state found for direct apply")
                return
            }

            val currentText = try {
                EditorUtil.getFileContent(virtualFile)
            } catch (e: Exception) {
                logger.error("Failed to read file content for direct apply", e)
                return
            }

            val containsText = currentText.contains(segment.search.trim())

            val newState = if (containsText) {
                stateManager.createFromSegment(segment)
            } else {
                stateManager.transitionToFailedDiffState(
                    segment.search,
                    segment.replace,
                    virtualFile
                ) ?: run {
                    logger.warn("Failed to transition to failed diff state")
                    return
                }
            }

            replaceEditor(oldEditor, newState.editor)

            val finalSegment =
                createReplaceWaitingSegment(searchContent, replaceContent, virtualFile)
            newState.updateContent(finalSegment)

            val currentEditor = newState.editor
            val headerPanel = currentEditor.permanentHeaderComponent as? DiffHeaderPanel

            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    headerPanel?.handleDone()
                    CompletionProgressNotifier.update(project, false)
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error during direct apply", e)
        }
    }

    override fun dispose() {
        val state = stateManager.getCurrentState()
        val editor = state?.editor ?: return
        val filePath = state.segment.filePath
        if (filePath != null) {
            DiffSyncManager.unregisterEditor(filePath, editor)
        }
    }

    private fun configureEditor(editor: EditorEx) {
        editor.document.addDocumentListener(object : BulkAwareDocumentListener.Simple {
            override fun documentChanged(event: DocumentEvent) {
                runInEdt {
                    updateEditorUI()
                    if (editor.editorKind != EditorKind.DIFF) {
                        scrollToEnd()
                    }
                }
            }
        })
    }

    private fun updateEditorUI() {
        updateEditorHeightAndUI()
        updateExpandLinkVisibility()
    }

    private fun updateEditorHeightAndUI() {
        val editor = stateManager.getCurrentState()?.editor ?: return
        ComponentFactory.updateEditorPreferredSize(
            editor,
            editor.getUserData(EXPANDED_KEY) == true
        )
    }

    private fun updateExpandLinkVisibility() {
        val editor = stateManager.getCurrentState()?.editor ?: return
        if (componentCount == 0 || getComponent(0) !== editor.component) {
            return
        }

        val lineCount = editor.document.lineCount
        val shouldShowExpandLink = lineCount >= MIN_LINES_FOR_EXPAND

        val hasExpandLink = componentCount > 1 && getComponent(1) != null

        if (shouldShowExpandLink && !hasExpandLink) {
            val expandLinkPanel = ComponentFactory.createExpandLinkPanel(editor)
            addToBottom(expandLinkPanel)
            revalidate()
            repaint()
        } else if (!shouldShowExpandLink && hasExpandLink) {
            if (componentCount > 1) {
                remove(getComponent(1))
                revalidate()
                repaint()
            }
        }
    }

    private fun scrollToEnd() {
        val editor = stateManager.getCurrentState()?.editor ?: return
        val textLength = editor.document.textLength
        if (textLength > 0) {
            val logicalPosition = editor.offsetToLogicalPosition(textLength - 1)
            editor.caretModel.moveToOffset(textLength - 1)
            editor.scrollingModel.scrollTo(
                LogicalPosition(logicalPosition.line, 0),
                ScrollType.MAKE_VISIBLE
            )
        }
    }
}
