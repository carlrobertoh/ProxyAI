package ee.carlrobert.codegpt.inlineedit

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.actions.editor.EditorComponentInlaysManager
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.inlineedit.engine.ApplyContext
import ee.carlrobert.codegpt.inlineedit.engine.InlineEditEngineImpl
import ee.carlrobert.codegpt.psistructure.PsiStructureProvider
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatMessageResponseBody
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.TotalTokensPanel
import ee.carlrobert.codegpt.ui.IconActionButton
import ee.carlrobert.codegpt.ui.components.BadgeChip
import ee.carlrobert.codegpt.ui.components.InlineEditChips
import ee.carlrobert.codegpt.ui.textarea.ConversationTagProcessor
import ee.carlrobert.codegpt.ui.textarea.TagProcessorFactory
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import ee.carlrobert.codegpt.util.GitUtil
import ee.carlrobert.codegpt.util.MarkdownUtil
import ee.carlrobert.codegpt.util.coroutines.CoroutineDispatchers
import kotlinx.coroutines.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.*

data class ObservableProperties(
    val submitted: AtomicBooleanProperty = AtomicBooleanProperty(false),
    val accepted: AtomicBooleanProperty = AtomicBooleanProperty(false),
    val loading: AtomicBooleanProperty = AtomicBooleanProperty(false),
)

class InlineEditInlay(private var editor: Editor) : Disposable {

    companion object {
        val INLAY_KEY: Key<InlineEditInlay> = Key.create("InlineEditInlay")
        private val logger = thisLogger()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val observableProperties = ObservableProperties()
    private val tagManager = TagManager()
    private var changesAccepted = false
    private var submissionHandler: InlineEditSubmissionHandler
    private var openedChatConversation: Conversation? = null

    private var isUpdatingInlaySize = false
    private var resizeTimer: Timer? = null

    private val project = requireNotNull(editor.project) { "Editor project is null" }
    private var inlayDisposable: Disposable? = null

    private val psiStructureRepository = PsiStructureRepository(
        this,
        project,
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
        project = project,
        totalTokensPanel = dummyTokensPanel,
        parentDisposable = this,
        featureType = FeatureType.INLINE_EDIT,
        tagManager = tagManager,
        onSubmit = { text ->
            handleSubmit(text)
        },
        onStop = {
            submissionHandler.handleStop()
        },
        onAcceptAll = {
            handleAcceptAll()
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
        withRemovableSelectedEditorTag = false
    ).apply {
        isOpaque = true
        setInlineEditControlsVisible(false)
        setThinkingVisible(false)

        preferredSize = Dimension(JBUI.scale(580), JBUI.scale(80))

        addPropertyChangeListener("preferredSize") { _ ->
            scheduleUpdateInlaySize()
        }
    }

    private var askResponseBody: ChatMessageResponseBody? = null
    private var askContainer: BorderLayoutPanel? = null
    private var askPopup: JBPopup? = null
    private var askApplyChip: BadgeChip? = null
    private var lastAssistantResponse: String = ""
    private val askMinHeight: Int = JBUI.scale(200)
    private val askMaxHeight: Int = JBUI.scale(520)

    private data class AskPanelContext(
        val editorEx: EditorEx,
        val editorComp: JComponent,
        val panelWidth: Int,
        val panelLeft: Int,
        val panelTop: Int,
        val panelBottom: Int,
        val spaceAbove: Int,
        val spaceBelow: Int,
        val placeAbove: Boolean,
    )

    private fun getAskPanelContext(): AskPanelContext? {
        val editorEx = editor as? EditorEx ?: return null
        val editorComp = editorEx.contentComponent
        val panelSize = if (mainContainer.width > 0) mainContainer.size else mainContainer.preferredSize
        val panelPoint = SwingUtilities.convertPoint(mainContainer, 0, 0, editorComp)
        val panelTop = panelPoint.y
        val panelBottom = panelTop + panelSize.height
        val visible = editorEx.scrollingModel.visibleArea
        val spaceAbove = panelTop - visible.y - JBUI.scale(6)

        return AskPanelContext(
            editorEx = editorEx,
            editorComp = editorComp,
            panelWidth = panelSize.width,
            panelLeft = panelPoint.x,
            panelTop = panelTop,
            panelBottom = panelBottom,
            spaceAbove = spaceAbove,
            spaceBelow = (visible.y + visible.height) - panelBottom - JBUI.scale(6),
            placeAbove = spaceAbove >= askMinHeight,
        )
    }

    private val mainContainer = BorderLayoutPanel().apply {
        isOpaque = true
        // Place input at SOUTH so it keeps its preferred height and doesn't stretch
        add(userInputPanel, BorderLayout.SOUTH)

        border = JBUI.Borders.empty(4, 8, 2, 8)
        background = userInputPanel.background ?: JBColor.background()

        isFocusable = true
        isFocusTraversalPolicyProvider = true

        cursor = Cursor.getDefaultCursor()
        focusTraversalPolicy = object : FocusTraversalPolicy() {
            override fun getFirstComponent(container: Container?) = userInputPanel
            override fun getLastComponent(container: Container?) = userInputPanel
            override fun getDefaultComponent(container: Container?) = userInputPanel
            override fun getComponentAfter(container: Container?, component: Component?) =
                userInputPanel

            override fun getComponentBefore(container: Container?, component: Component?) =
                userInputPanel
        }

        val fixedWidth = JBUI.scale(600)
        val initialHeight = JBUI.scale(100)
        val minHeight = JBUI.scale(60)
        preferredSize = Dimension(fixedWidth, initialHeight)
        maximumSize = Dimension(fixedWidth, Integer.MAX_VALUE)
        minimumSize = Dimension(fixedWidth, minHeight)
        putClientProperty("codegpt.fixedWidth", 600)

        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                restoreFocus()
            }
        })

        val inputMap = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = actionMap

        inputMap.put(KeyStroke.getKeyStroke("F2"), "forceFocus")
        actionMap.put("forceFocus", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                logger.debug("F2 pressed - forcing focus")
                restoreFocus()
            }
        })

        inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            "closeInlay"
        )
        actionMap.put("closeInlay", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                logger.debug("ESC pressed - closing inlay")
                close()
            }
        })
    }

    init {
        editor.putUserData(INLAY_KEY, this)

        val editorEx = editor as EditorEx
        val sessionConversation = Conversation().apply {
            projectPath = editorEx.project?.basePath
            val fileName = editorEx.virtualFile?.name ?: CodeGPTBundle.get("inlineEdit.conversation.untitled")
            title = CodeGPTBundle.get("inlineEdit.conversation.title", fileName)
        }
        submissionHandler =
            InlineEditSubmissionHandler(editor, observableProperties, sessionConversation)
    }

    fun isQuickQuestionEnabled() = userInputPanel.isQuickQuestionEnabled()

    private fun buildAskContainer(): JComponent {
        val container = BorderLayoutPanel().apply {
            isOpaque = true
            background = userInputPanel.background ?: JBColor.background()
            border = JBUI.Borders.empty(6, 8)
            minimumSize = Dimension(JBUI.scale(600), JBUI.scale(400))
        }

        val responseBody = ChatMessageResponseBody(
            project,
            true,
            this
        ).apply {
            isOpaque = false
        }
        askResponseBody = responseBody

        val scrollPane = JBScrollPane(responseBody).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            isOpaque = false
            viewport.isOpaque = false
        }

        val copyButton = IconActionButton(
            object :
                AnAction(
                    CodeGPTBundle.get("shared.copy"),
                    CodeGPTBundle.get("shared.copyToClipboard"),
                    com.intellij.icons.AllIcons.Actions.Copy
                ) {
                override fun actionPerformed(e: AnActionEvent) {
                    val text = lastAssistantResponse
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(StringSelection(text), null)
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = lastAssistantResponse.isNotBlank()
                }
            },
            "COPY_MD"
        )

        val applyChip = BadgeChip(CodeGPTBundle.get("shared.apply"), InlineEditChips.GREEN, { handleApply() }).apply {
            isVisible = false
            isEnabled = false
        }
        askApplyChip = applyChip

        val header = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(4))).apply {
            isOpaque = false
            add(copyButton)
            add(applyChip)
        }

        container.add(header, BorderLayout.NORTH)
        container.add(scrollPane, BorderLayout.CENTER)

        askContainer = container
        return container
    }

    private fun showAskPopup() {
        runInEdt {
            if (askPopup?.isVisible == true) return@runInEdt
            val content = askContainer ?: buildAskContainer()
            val ctx = getAskPanelContext() ?: return@runInEdt
            val availableHeight = if (ctx.placeAbove) ctx.spaceAbove else ctx.spaceBelow
            val pref = content.preferredSize
            val targetH =
                pref.height.coerceIn(askMinHeight, askMaxHeight).coerceAtMost(availableHeight)
                    .coerceAtLeast(askMinHeight)

            content.preferredSize = Dimension(ctx.panelWidth, targetH)

            val builder = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, null)
                .setRequestFocus(false)
                .setFocusable(false)
                .setResizable(true)
                .setMovable(true)
                .setCancelOnClickOutside(false)
                .setCancelOnOtherWindowOpen(false)
                .setCancelOnWindowDeactivation(false)
                .setMinSize(Dimension(ctx.panelWidth, askMinHeight))

            val popup = builder.createPopup()
            askPopup = popup

            popup.size = Dimension(ctx.panelWidth, targetH)

            val margin = JBUI.scale(6)
            val anchorPoint = if (ctx.placeAbove) {
                Point(ctx.panelLeft, ctx.panelTop + targetH - margin)
            } else {
                Point(ctx.panelLeft, ctx.panelBottom + margin)
            }
            popup.show(RelativePoint(ctx.editorComp, anchorPoint))
            adjustAskPopupSize()
        }
    }

    fun hideAskPopup() {
        runInEdt {
            askPopup?.cancel()
            askPopup = null
            resetApplyChip()
            askResponseBody?.clear()
        }
    }

    fun resetAskContainer() {
        runInEdt {
            showAskPopup()
            askResponseBody?.clear()
            resetApplyChip()
        }
    }

    fun updateAskResponseStream(partial: String) {
        runInEdt {
            showAskPopup()
            askResponseBody?.updateMessage(partial)
            adjustAskPopupSize()
        }
    }

    fun setAskLastAssistantResponse(content: String) {
        lastAssistantResponse = content
        val hasBlocks = MarkdownUtil.extractCodeBlocks(content).isNotEmpty()
        setApplyChip(enabled = hasBlocks)
    }

    fun updateApplyVisibilityAfterComplete(fullMessage: String) {
        val hasBlocks = MarkdownUtil.extractCodeBlocks(fullMessage).isNotEmpty()
        setApplyChip(enabled = hasBlocks, visible = hasBlocks)
        adjustAskPopupSize()
    }

    private fun adjustAskPopupSize() {
        val popup = askPopup ?: return
        val container = askContainer ?: return
        val ctx = getAskPanelContext() ?: return

        container.revalidate()
        val pref = container.preferredSize
        val targetH = pref.height.coerceIn(askMinHeight, askMaxHeight)

        if (popup.size.width != ctx.panelWidth || popup.size.height != targetH) {
            popup.size = Dimension(ctx.panelWidth, targetH)
        }

        val margin = JBUI.scale(6)
        val anchorPoint = if (ctx.placeAbove) {
            Point(ctx.panelLeft, ctx.panelTop - targetH - margin)
        } else {
            Point(ctx.panelLeft, ctx.panelBottom + margin)
        }
        popup.setLocation(RelativePoint(ctx.editorComp, anchorPoint).screenPoint)
    }

    private fun resetApplyChip() {
        askApplyChip?.isVisible = false
        askApplyChip?.isEnabled = false
    }

    private fun setApplyChip(enabled: Boolean, visible: Boolean? = null) {
        askApplyChip?.isEnabled = enabled
        if (visible != null) askApplyChip?.isVisible = visible
    }

    private fun handleApply() {
        hideAskPopup()

        val editorEx = editor as? EditorEx ?: return
        val ctx = ApplyContext(
            editorEx,
            this,
            submissionHandler,
            userInputPanel.text,
            lastAssistantResponse
        )
        InlineEditEngineImpl().apply(ctx)
    }

    @RequiresEdt
    fun show() {
        try {
            mainContainer.border = JBUI.Borders.empty(8, 12, 8, 12)

            val hasSelection = editor.selectionModel.hasSelection()
            val offset = if (hasSelection) {
                editor.selectionModel.selectionStart
            } else {
                editor.caretModel.offset
            }

            inlayDisposable = EditorComponentInlaysManager
                .from(editor)
                .insert(offset, mainContainer, showAbove = hasSelection)

            mainContainer.revalidate()
            mainContainer.repaint()
            userInputPanel.revalidate()
            userInputPanel.repaint()
            restoreFocus()

            project.messageBus.connect(this).subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun selectionChanged(event: FileEditorManagerEvent) {
                        val newEditor =
                            FileEditorManager.getInstance(project).selectedTextEditor ?: return

                        if (newEditor !== this@InlineEditInlay.editor) {
                            logger.debug("Editor switched - closing inline edit inlay")
                            close()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("Failed to show inline edit inlay", e)
            dispose()
        }
    }

    fun close() {
        runInEdt {
            try {
                val session = editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
                val accepted = observableProperties.accepted.get()
                val renderer = editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)
                val hasPending = (session?.hasPendingHunks() == true) || (renderer?.hasPendingChanges() == true)
                val shouldPrompt = !accepted && hasPending

                if (shouldPrompt) {
                    val result = Messages.showYesNoDialog(
                        project,
                        CodeGPTBundle.get("inlineEdit.closeWarning.message"),
                        CodeGPTBundle.get("inlineEdit.closeWarning.title"),
                        CodeGPTBundle.get("inlineEdit.closeWarning.closeAnyway"),
                        CodeGPTBundle.get("shared.cancel"),
                        Messages.getWarningIcon()
                    )
                    if (result != Messages.YES) {
                        return@runInEdt
                    }

                    invokeLater { submissionHandler.handleReject() }
                }

                logger.debug("Closing inline edit inlay")
                dispose()
            } catch (e: Exception) {
                logger.error("Failed to close inlay cleanly", e)
                dispose()
            }
        }
    }

    override fun dispose() {
        serviceScope.cancel()
        inlayDisposable?.dispose()
        editor.putUserData(INLAY_KEY, null)
        askResponseBody = null
        askContainer = null
        askPopup?.cancel()
        askPopup = null
    }

    fun openOrCreateChatFromSession(sessionConversation: Conversation) {
        val project = editor.project ?: return
        val chat = openedChatConversation
        if (chat != null) {
            project.service<ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager>()
                .displayConversation(chat)
            return
        }

        val newConversation = Conversation().apply {
            title = sessionConversation.title
            projectPath = sessionConversation.projectPath
            messages = sessionConversation.messages
        }
        ee.carlrobert.codegpt.conversations.ConversationService.getInstance()
            .addConversation(newConversation)
        ee.carlrobert.codegpt.conversations.ConversationService.getInstance()
            .saveConversation(newConversation)
        openedChatConversation = newConversation
        project.service<ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager>()
            .displayConversation(newConversation)
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

    fun setThinkingVisible(visible: Boolean, text: String = CodeGPTBundle.get("shared.thinking")) {
        userInputPanel.setThinkingVisible(visible, text)
    }

    fun restorePromptAndFocus(promptText: String) {
        runInEdt {
            userInputPanel.setTextAndFocus(promptText)
            Timer(50) {
                restoreFocus()
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    fun restorePreviousPrompt() {
        submissionHandler.restorePreviousPrompt()
    }

    private fun scheduleUpdateInlaySize(delayMs: Int = 24) {
        if (resizeTimer == null) {
            resizeTimer = Timer(delayMs) {
                resizeTimer?.stop()
                resizeTimer = null
                updateInlaySize()
            }.apply { isRepeats = false }
        }
        resizeTimer?.stop()
        resizeTimer?.initialDelay = delayMs
        resizeTimer?.start()
    }

    private fun updateInlaySize() {
        if (isUpdatingInlaySize) return
        isUpdatingInlaySize = true
        runInEdt {
            try {
                userInputPanel.invalidate()
                mainContainer.invalidate()

                val childPref = userInputPanel.preferredSize
                val border = (mainContainer.border?.getBorderInsets(mainContainer))
                    ?: Insets(0, 0, 0, 0)
                val newPref = Dimension(
                    JBUI.scale(600),
                    (childPref.height + border.top + border.bottom)
                )

                val oldPref = mainContainer.preferredSize
                val oldSize = mainContainer.size
                val preferredSize = newPref

                logger.debug("Updating inlay size - old: ${oldSize.width}x${oldSize.height}, preferred: ${preferredSize.width}x${preferredSize.height}")

                if (oldPref != preferredSize) {
                    mainContainer.preferredSize = preferredSize
                    mainContainer.size = preferredSize
                    mainContainer.revalidate()
                    mainContainer.repaint()
                }

                userInputPanel.revalidate()
                mainContainer.revalidate()

                userInputPanel.repaint()
                mainContainer.repaint()

                inlayDisposable?.let { disposable ->
                    val parent = mainContainer.parent
                    parent?.invalidate()
                    parent?.revalidate()
                    parent?.repaint()

                    editor.contentComponent.invalidate()
                    editor.contentComponent.revalidate()
                    editor.contentComponent.repaint()

                    (editor as? EditorEx)?.scrollPane?.let { scrollPane ->
                        scrollPane.invalidate()
                        scrollPane.revalidate()
                        scrollPane.repaint()
                    }
                }

                val newSize = mainContainer.preferredSize
                logger.debug("Inlay size updated to: ${newSize.width}x${newSize.height}")

            } catch (e: Exception) {
                logger.error("Error updating inlay size", e)
            } finally {
                isUpdatingInlaySize = false
            }
        }
    }

    private fun restoreFocus() {
        runInEdt {
            try {
                ensureProperSizing()

                if (!userInputPanel.isVisible || !userInputPanel.isEnabled) {
                    logger.warn("UserInputPanel is not visible or enabled - making it so")
                    userInputPanel.isVisible = true
                    userInputPanel.isEnabled = true
                }

                val focusGained = userInputPanel.requestFocusInWindow()
                if (!focusGained) {
                    userInputPanel.requestFocus()
                }

                val preferredComponent = userInputPanel.getPreferredFocusedComponent()
                preferredComponent?.let { component ->
                    component.requestFocusInWindow()
                    logger.debug("Requesting focus on preferred component: ${component.javaClass.simpleName}")
                }

                val hasFocus = userInputPanel.hasFocus()
                val isFocusOwner = userInputPanel.isFocusOwner
                val focusOwner =
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner

                logger.debug("Focus state - UserInputPanel hasFocus: $hasFocus, isFocusOwner: $isFocusOwner")
                logger.debug("Current focus owner: ${focusOwner?.javaClass?.simpleName ?: "null"}")

                if (!hasFocus && !isFocusOwner) {
                    Timer(100) {
                        logger.debug("Retrying focus after 100ms")
                        userInputPanel.requestFocusInWindow()
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to restore focus", e)
            }
        }
    }

    private fun ensureProperSizing() {
        try {
            val currentHeight = userInputPanel.height
            val preferredHeight = userInputPanel.preferredSize.height
            val minHeight = 60

            logger.debug("Current sizing - Height: $currentHeight, Preferred: $preferredHeight")

            if (currentHeight > 0 && currentHeight < minHeight) {
                logger.debug("Adjusting minimum size to ensure visibility")

                userInputPanel.minimumSize = Dimension(650, minHeight)

                userInputPanel.revalidate()
                mainContainer.revalidate()
                userInputPanel.repaint()
                mainContainer.repaint()
            }
        } catch (e: Exception) {
            logger.error("Error ensuring proper sizing", e)
        }
    }

    private fun handleAcceptAll() {
        editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.acceptAll()
            ?: editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)?.acceptAll()

        runInEdt {
            userInputPanel.setTextAndFocus("")
        }
    }

    private fun handleSubmit(text: String) {
        if (text.isNotEmpty()) {
            observableProperties.submitted.set(true)
            userInputPanel.setSubmitEnabled(false)

            serviceScope.launch {
                try {
                    val refs = collectSelectedReferencedFiles()
                    val diff = try {
                        GitUtil.getCurrentChanges(project)
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
                    is FolderTagDetails -> it.folder // TODO
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
}
