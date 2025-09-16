package ee.carlrobert.codegpt.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.inlineedit.InlineEditConversationManager
import ee.carlrobert.codegpt.inlineedit.InlineEditSubmissionHandler
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.psistructure.PsiStructureProvider
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.TotalTokensPanel
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.HistoryTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.DiagnosticsTagDetails
import ee.carlrobert.codegpt.ui.textarea.ConversationTagProcessor
import ee.carlrobert.codegpt.ui.textarea.TagProcessorFactory
import ee.carlrobert.codegpt.util.GitUtil
import ee.carlrobert.codegpt.util.coroutines.CoroutineDispatchers
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

data class ObservableProperties(
    val submitted: AtomicBooleanProperty = AtomicBooleanProperty(false),
    val accepted: AtomicBooleanProperty = AtomicBooleanProperty(false),
    val loading: AtomicBooleanProperty = AtomicBooleanProperty(false),
    val hasPendingChanges: AtomicBooleanProperty = AtomicBooleanProperty(false),
)

class InlineEditPopover(private var editor: Editor) : Disposable {

    companion object {
        val POPOVER_KEY: Key<InlineEditPopover> = Key.create("InlineEditPopover")
        private val logger = thisLogger()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val observableProperties = ObservableProperties()
    private val tagManager = TagManager(this)
    private var changesAccepted = false
    private var submissionHandler = InlineEditSubmissionHandler(editor, observableProperties)

    private val project = editor.project!!

    private val psiStructureRepository = PsiStructureRepository(
        this,
        editor.project!!,
        tagManager,
        PsiStructureProvider(),
        CoroutineDispatchers()
    )

    private val dummyTokensPanel = TotalTokensPanel(
        Conversation(),
        null,
        this,
        psiStructureRepository
    )

    private val userInputPanel = UserInputPanel(
        project = editor.project!!,
        totalTokensPanel = dummyTokensPanel,
        parentDisposable = this,
        featureType = FeatureType.INLINE_EDIT,
        tagManager = tagManager,
        onSubmit = { text ->
            handleSubmit(text)
        },
        onStop = {
            submissionHandler.handleReject(clearConversation = false)
        },
        onAcceptAll = {
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.acceptAll()
                ?: editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)?.acceptAll()
        },
        onRejectAll = {
            val session = editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
            val renderer = editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)

            if (session != null) {
                session.rejectAll()
                submissionHandler.restorePreviousPrompt()
            } else if (renderer != null) {
                renderer.rejectAll()
                submissionHandler.restorePreviousPrompt()
            }
        },
        showModeSelector = false,
        withRemovableSelectedEditorTag = false
    ).apply {
        isOpaque = true
        setInlineEditControlsVisible(false)
        setThinkingVisible(false)
    }

    private val userInputWrapper = BorderLayoutPanel().apply {
        isOpaque = false
        border = JBUI.Borders.empty()
        add(userInputPanel, BorderLayout.CENTER)
    }.andTransparent()

    private val mainContainer = object : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            background = null
            add(userInputWrapper)
        }

        override fun paintComponent(g: Graphics?) {
        }
    }

    private val popup = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(mainContainer, userInputPanel)
        .setMovable(true)
        .setResizable(true)
        .setCancelKeyEnabled(true)
        .setCancelOnClickOutside(false)
        .setCancelOnWindowDeactivation(false)
        .setRequestFocus(true)
        .setMinSize(Dimension(600, 80))
        .setShowBorder(false)
        .setShowShadow(false)
        .setAdText("")
        .setCancelCallback {
            if (!changesAccepted) {
                submissionHandler.handleReject(clearConversation = true)
            }
            true
        }
        .createPopup()

    init {
        Disposer.register(popup, this)
        userInputPanel.requestFocus()

        editor.putUserData(POPOVER_KEY, this)
    }

    fun show() {
        val point = computePopupPoint(editor)
        popup.show(point)

        invokeLater {
            userInputPanel.requestFocus()

            SwingUtilities.getWindowAncestor(popup.content)?.let { window ->
                try {
                    window.background = Color(0, 0, 0, 0)

                    if (window is javax.swing.JWindow) {
                        window.contentPane.background = Color(0, 0, 0, 0)
                        if (window.contentPane is JComponent) {
                            (window.contentPane as JComponent).isOpaque = false
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to make window transparent: ${e.message}")
                }
            }
        }

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val newEditor =
                        FileEditorManager.getInstance(project).selectedTextEditor ?: return
                    if (newEditor === this@InlineEditPopover.editor) return
                    attachToEditor(newEditor)
                }
            }
        )
    }

    override fun dispose() {
        serviceScope.cancel()
        editor.putUserData(POPOVER_KEY, null)
    }

    fun onCompletionFinished() {
        runInEdt {
            userInputPanel.setSubmitEnabled(true)
            observableProperties.submitted.set(false)
            setThinkingVisible(false)
        }
    }

    fun markChangesAsAccepted() {
        changesAccepted = true
        observableProperties.accepted.set(true)
        FileDocumentManager.getInstance().saveDocument(editor.document)
    }

    fun setInlineEditControlsVisible(visible: Boolean) {
        userInputPanel.setInlineEditControlsVisible(visible)
    }

    fun setThinkingVisible(visible: Boolean, text: String = "Thinking…") {
        userInputPanel.setThinkingVisible(visible, text)
    }

    fun restorePromptAndFocus(promptText: String) {
        runInEdt {
            userInputPanel.setTextAndFocus(promptText)
        }
    }

    fun triggerPromptRestoration() {
        submissionHandler.restorePreviousPrompt()
    }

    private fun handleSubmit(text: String) {
        if (text.isNotEmpty()) {
            observableProperties.submitted.set(true)
            userInputPanel.setSubmitEnabled(false)

            serviceScope.launch {
                try {
                    val refs = collectSelectedReferencedFiles()
                    val diff = try {
                        GitUtil.getCurrentChanges(editor.project!!)
                    } catch (_: Exception) {
                        null
                    }
                    val conversationHistory = collectConversationHistory()
                    val diagnosticsInfo = collectDiagnosticsInfo()
                    submissionHandler.handleSubmit(
                        text,
                        refs,
                        diff,
                        conversationHistory,
                        diagnosticsInfo
                    )
                } catch (e: Exception) {
                    logger.error("Error submitting inline edit", e)
                    runInEdt {
                        userInputPanel.setSubmitEnabled(true)
                        observableProperties.submitted.set(false)
                    }
                }
            }
        }
    }

    private fun collectConversationHistory(): List<Conversation> {
        val tags: Set<TagDetails> = tagManager.getTags()
        return tags
            .filter { it.selected && it is HistoryTagDetails }
            .map { (it as HistoryTagDetails).conversationId }
            .mapNotNull { ConversationTagProcessor.getConversation(it) }
            .distinct()
    }

    private fun collectSelectedReferencedFiles(): List<ReferencedFile> {
        val tags: Set<TagDetails> = tagManager.getTags()
        val currentPath = editor.virtualFile?.path
        val selectedVfs = tags
            .filter { it.selected }
            .mapNotNull {
                when (it) {
                    is FileTagDetails -> it.virtualFile
                    is EditorTagDetails -> it.virtualFile
                    else -> null
                }
            }
            .filter { vf -> vf.path != currentPath }
            .distinctBy { it.path }

        return selectedVfs.mapNotNull { v ->
            try {
                ReferencedFile.from(v)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun collectDiagnosticsInfo(): String? {
        val tags: Set<TagDetails> = tagManager.getTags()
        val diagnosticsTag =
            tags.firstOrNull { it.selected && it is DiagnosticsTagDetails } as? DiagnosticsTagDetails
                ?: return null

        val processor = TagProcessorFactory.getProcessor(project, diagnosticsTag)
        val stringBuilder = StringBuilder()
        processor.process(Message("", ""), stringBuilder)
        return stringBuilder.toString().takeIf { it.isNotBlank() }
    }

    private fun computePopupPoint(targetEditor: Editor): RelativePoint {
        val editorComponent = targetEditor.component
        val popupWidth = 600
        val popupHeight = mainContainer.preferredSize.height.coerceAtLeast(150)

        val editorWidth = editorComponent.width
        var x = (editorWidth - popupWidth) / 2
        if (x < 0) x = 0
        val paddingFromBottom = 20
        val y = editorComponent.height - popupHeight - paddingFromBottom
        return RelativePoint(editorComponent, java.awt.Point(x, y))
    }

    private fun attachToEditor(newEditor: Editor) {
        val oldEx = this.editor as? EditorEx
        val newEx = newEditor as? EditorEx

        this.editor.putUserData(POPOVER_KEY, null)
        this.editor = newEditor
        this.editor.putUserData(POPOVER_KEY, this)

        InlineEditConversationManager.moveConversation(oldEx, newEx)

        submissionHandler = InlineEditSubmissionHandler(newEditor, observableProperties)

        val point = computePopupPoint(newEditor)
        popup.setLocation(point.screenPoint)
    }
}
