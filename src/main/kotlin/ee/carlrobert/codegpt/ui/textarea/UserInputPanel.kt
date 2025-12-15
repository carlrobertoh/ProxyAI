package ee.carlrobert.codegpt.ui.textarea

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.notification.NotificationType
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.IconUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.agent.PromptEnhancer
import ee.carlrobert.codegpt.settings.configuration.ChatMode
import ee.carlrobert.codegpt.settings.models.ModelRegistry
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.ModelComboBoxAction
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.TotalTokensPanel
import ee.carlrobert.codegpt.ui.IconActionButton
import ee.carlrobert.codegpt.ui.components.BadgeChip
import ee.carlrobert.codegpt.ui.components.InlineEditChips
import ee.carlrobert.codegpt.ui.dnd.FileDragAndDrop
import ee.carlrobert.codegpt.ui.textarea.header.UserInputHeaderPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import git4idea.GitCommit
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel

class UserInputPanel @JvmOverloads constructor(
    private val project: Project,
    private val totalTokensPanel: TotalTokensPanel,
    private val parentDisposable: Disposable,
    private val featureType: FeatureType,
    val tagManager: TagManager,
    private val onSubmit: (String) -> Unit,
    private val onStop: () -> Unit,
    private val onAcceptAll: (() -> Unit)? = null,
    private val onRejectAll: (() -> Unit)? = null,
    private val onApply: (() -> Unit)? = null,
    private val getMarkdownContent: (() -> String)? = null,
    withRemovableSelectedEditorTag: Boolean = true,
    private val agentTokenCounterPanel: JComponent? = null,
    private val sessionIdProvider: (() -> String?)? = null,
) : BorderLayoutPanel() {

    constructor(
        project: Project,
        totalTokensPanel: TotalTokensPanel,
        parentDisposable: Disposable,
        featureType: FeatureType,
        tagManager: TagManager,
        onSubmit: (String) -> Unit,
        onStop: () -> Unit,
        withRemovableSelectedEditorTag: Boolean
    ) : this(
        project,
        totalTokensPanel,
        parentDisposable,
        featureType,
        tagManager,
        onSubmit,
        onStop,
        null,
        null,
        null,
        null,
        withRemovableSelectedEditorTag
    )

    companion object {
        private const val CORNER_RADIUS = 16
    }

    private val quickQuestionCheckbox = JBCheckBox(CodeGPTBundle.get("userInput.quickQuestion"), true).apply {
        isOpaque = false
    }
    private var chatMode: ChatMode = ChatMode.ASK
    private val disposableCoroutineScope = DisposableCoroutineScope()
    private val promptTextField =
        PromptTextField(
            project = project,
            tagManager = tagManager,
            onTextChanged = ::updateUserTokens,
            onBackSpace = ::handleBackSpace,
            onLookupAdded = ::handleLookupAdded,
            onSubmit = ::handleSubmit,
            onFilesDropped = { files ->
                includeFiles(files.toMutableList())
            },
            featureType = featureType,
        )
    private val userInputHeaderPanel =
        UserInputHeaderPanel(
            project,
            tagManager,
            totalTokensPanel,
            promptTextField,
            withRemovableSelectedEditorTag,
            onApply,
            getMarkdownContent,
            featureType
        )

    private var footerPanelRef: JPanel? = null

    private val applyChip =
        onApply?.let { BadgeChip(CodeGPTBundle.get("shared.apply"), InlineEditChips.GREEN, it) }?.apply {
            isVisible = false
        }
    private val acceptChip =
        InlineEditChips.acceptAll { onAcceptAll?.invoke() }.apply { isVisible = false }
    private val rejectChip =
        InlineEditChips.rejectAll { onRejectAll?.invoke() }.apply { isVisible = false }
    private var inlineEditControls: List<JComponent> = listOf(acceptChip, rejectChip)

    private val thinkingIcon = AsyncProcessIcon("inline-edit-thinking").apply { isVisible = false }
    private val thinkingLabel = javax.swing.JLabel(CodeGPTBundle.get("shared.thinking")).apply {
        foreground = service<EditorColorsManager>().globalScheme.defaultForeground
        isVisible = false
    }
    private val thinkingPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(thinkingIcon)
            add(thinkingLabel)
            isVisible = false
        }

    private val submitButton = IconActionButton(
        object : AnAction(
            CodeGPTBundle.get("smartTextPane.submitButton.title"),
            CodeGPTBundle.get("smartTextPane.submitButton.description"),
            IconUtil.scale(Icons.Send, null, 0.85f)
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                handleSubmit(promptTextField.getExpandedText())
            }
        },
        "SUBMIT"
    ).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val stopButton = IconActionButton(
        object : AnAction(
            CodeGPTBundle.get("smartTextPane.stopButton.title"),
            CodeGPTBundle.get("smartTextPane.stopButton.description"),
            AllIcons.Actions.Suspend
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                onStop()
            }
        },
        "STOP"
    ).apply {
        isEnabled = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val promptEnhancer = if (featureType == FeatureType.AGENT) {
        PromptEnhancer(project)
    } else {
        null
    }
    private val promptEnhancerButton = if (featureType == FeatureType.AGENT) {
        IconActionButton(
            object : AnAction(
                CodeGPTBundle.get("smartTextPane.promptEnhancer.title"),
                CodeGPTBundle.get("smartTextPane.promptEnhancer.description"),
                IconUtil.scale(Icons.Sparkle, null, 0.85f)
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    handlePromptEnhancer()
                }
            },
            "PROMPT_ENHANCER"
        ).apply {
            isEnabled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    } else {
        null
    }
    private val promptEnhancerSeparator = if (featureType == FeatureType.AGENT) {
        createActionSeparator()
    } else {
        null
    }
    private val imageActionSupported = AtomicBooleanProperty(isImageActionSupported())

    private lateinit var modelComboBoxComponent: JComponent
    private var separatorRef: JPanel? = null
    private var isPromptEnhancing: Boolean = false

    val text: String
        get() = promptTextField.text

    fun isQuickQuestionEnabled(): Boolean = quickQuestionCheckbox.isSelected
    fun getChatMode(): ChatMode = chatMode
    fun setChatMode(mode: ChatMode) {
        chatMode = mode
    }


    init {
        setupDisposables(parentDisposable)
        setupLayout(featureType)
        addSelectedEditorContent()
        if (featureType == FeatureType.INLINE_EDIT) {
            setupTextChangeListener()
        }
        FileDragAndDrop.install(this) { files ->
            includeFiles(files.toMutableList())
        }
    }

    private fun setupDisposables(parentDisposable: Disposable) {
        Disposer.register(parentDisposable, disposableCoroutineScope)
        Disposer.register(parentDisposable, promptTextField)
    }

    private fun setupLayout(featureType: FeatureType) {
        background = service<EditorColorsManager>().globalScheme.defaultBackground
        isFocusable = true
        cursor = Cursor.getDefaultCursor()

        addToTop(userInputHeaderPanel)
        addToCenter(promptTextField)
        addToBottom(createToolbarSeparator().also { separatorRef = it })
        addToBottom(createFooterPanel(featureType).also { footerPanelRef = it })

        if (featureType == FeatureType.INLINE_EDIT) {
            invokeLater { updatePreferredSizeFromChildren() }
            minimumSize = Dimension(JBUI.scale(600), JBUI.scale(80))

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    promptTextField.requestFocusInWindow()
                }
            })
        }
    }

    private fun setupTextChangeListener() {
        promptTextField.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                runInEdt {
                    updatePreferredSizeFromChildren()
                }
            }
        }, disposableCoroutineScope)

        promptTextField.addPropertyChangeListener("preferredSize") { _ ->
            runInEdt { updatePreferredSizeFromChildren() }
        }
        userInputHeaderPanel.addPropertyChangeListener("preferredSize") { _ ->
            runInEdt { updatePreferredSizeFromChildren() }
        }
        footerPanelRef?.addPropertyChangeListener("preferredSize") { _ ->
            runInEdt { updatePreferredSizeFromChildren() }
        }
    }

    private fun addSelectedEditorContent() {
        runInEdt {
            EditorUtil.getSelectedEditor(project)?.let { editor ->
                if (EditorUtil.hasSelection(editor)) {
                    tagManager.addTag(
                        EditorSelectionTagDetails(editor.virtualFile, editor.selectionModel)
                    )
                }
            }
        }
    }

    fun getSelectedTags(): List<TagDetails> {
        return userInputHeaderPanel.getSelectedTags()
    }

    fun setSubmitEnabled(enabled: Boolean) {
        submitButton.isEnabled = enabled
    }

    fun setStopEnabled(enabled: Boolean) {
        stopButton.isEnabled = enabled
    }

    fun addSelection(editorFile: VirtualFile, selectionModel: SelectionModel) {
        addTag(SelectionTagDetails(editorFile, selectionModel))
        promptTextField.requestFocusInWindow()
        selectionModel.removeSelection()
    }

    fun addCommitReferences(gitCommits: List<GitCommit>) {
        runInEdt {
            setCommitPromptIfEmpty(gitCommits)
            addCommitTags(gitCommits)
            focusOnPromptEnd()
        }
    }

    private fun setCommitPromptIfEmpty(gitCommits: List<GitCommit>) {
        if (promptTextField.text.isEmpty()) {
            promptTextField.text = buildCommitPrompt(gitCommits)
        }
    }

    private fun buildCommitPrompt(gitCommits: List<GitCommit>): String {
        return if (gitCommits.size == 1) {
            "Explain the commit `${gitCommits[0].id.toShortString()}`"
        } else {
            "Explain the commits ${gitCommits.joinToString(", ") { "`${it.id.toShortString()}`" }}"
        }
    }

    private fun addCommitTags(gitCommits: List<GitCommit>) {
        gitCommits.forEach { addTag(GitCommitTagDetails(it)) }
    }

    private fun focusOnPromptEnd() {
        promptTextField.requestFocusInWindow()
        promptTextField.editor?.caretModel?.moveToOffset(promptTextField.text.length)
    }

    fun addTag(tagDetails: TagDetails) {
        userInputHeaderPanel.addTag(tagDetails)
        removeTrailingAtSymbol()
    }

    private fun removeTrailingAtSymbol() {
        val text = promptTextField.text
        if (text.endsWith('@')) {
            promptTextField.text = text.dropLast(1)
        }
    }

    fun includeFiles(referencedFiles: MutableList<VirtualFile>) {
        referencedFiles.forEach { vf ->
            if (vf.isDirectory) {
                userInputHeaderPanel.addTag(FolderTagDetails(vf))
            } else {
                userInputHeaderPanel.addTag(FileTagDetails(vf))
            }
        }
    }

    override fun requestFocus() {
        invokeLater {
            promptTextField.requestFocusInWindow()
        }
    }

    override fun requestFocusInWindow(): Boolean {
        return promptTextField.requestFocusInWindow()
    }

    fun setTextAndFocus(text: String) {
        promptTextField.setTextAndFocus(text)
    }

    fun clearText() {
        promptTextField.clear()
    }

    override fun isFocusable(): Boolean = true

    fun getPreferredFocusedComponent(): JComponent? = promptTextField

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            setupGraphics(g2)
            drawRoundedBackground(g2)
            super.paintComponent(g2)
        } finally {
            g2.dispose()
        }
    }

    private fun setupGraphics(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    }

    private fun drawRoundedBackground(g2: Graphics2D) {
        val area = createRoundedArea()
        g2.clip = area
        g2.color = background
        g2.fill(area)
    }

    private fun createRoundedArea(): Area {
        val bounds = Rectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat())
        val roundedRect = RoundRectangle2D.Float(
            0f, 0f, width.toFloat(), height.toFloat(),
            CORNER_RADIUS.toFloat(), CORNER_RADIUS.toFloat()
        )
        val area = Area(bounds)
        area.intersect(Area(roundedRect))
        return area
    }

    override fun paintBorder(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            setupGraphics(g2)
            drawRoundedBorder(g2)
        } finally {
            g2.dispose()
        }
    }

    private fun drawRoundedBorder(g2: Graphics2D) {
        g2.color = JBUI.CurrentTheme.Focus.defaultButtonColor()
        if (promptTextField.isFocusOwner || dragActive) {
            g2.stroke = BasicStroke(1.5F)
        }
        g2.drawRoundRect(0, 0, width - 1, height - 1, CORNER_RADIUS, CORNER_RADIUS)
    }

    private var dragActive: Boolean = false

    fun setDragActive(active: Boolean) {
        dragActive = active
        repaint()
    }

    override fun getInsets(): Insets = JBUI.insets(4)

    private fun handleSubmit(text: String) {
        if (text.isNotEmpty() && submitButton.isEnabled) {
            onSubmit(text)
            promptTextField.clear()
        }
    }

    private fun updateUserTokens(text: String) {
        val expanded = promptTextField.getExpandedText()
        totalTokensPanel.updateUserPromptTokens(expanded)
        updatePromptEnhancerState(expanded)
    }

    private fun handleBackSpace() {
        if (text.isEmpty()) {
            userInputHeaderPanel.getLastTag()?.let { last ->
                if (last.isRemovable) {
                    tagManager.remove(last)
                }
            }
        }
    }

    private fun handleLookupAdded(item: LookupActionItem) {
        item.execute(project, this)
    }

    private fun handlePromptEnhancer() {
        val enhancer = promptEnhancer ?: return
        val sourceText = promptTextField.getExpandedText().trim()
        if (sourceText.isBlank() || isPromptEnhancing) return
        val tags = getSelectedTags()
        val sessionId = sessionIdProvider?.invoke()

        isPromptEnhancing = true
        promptEnhancerButton?.isEnabled = false
        setThinkingVisible(true, CodeGPTBundle.get("promptEnhancer.thinking"))

        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { enhancer.enhancePrompt(sourceText, tags, sessionId) }
                .onSuccess { result ->
                    runInEdt {
                        if (Disposer.isDisposed(parentDisposable)) return@runInEdt
                        if (promptTextField.getExpandedText().trim() == sourceText) {
                            promptTextField.text = result.prompt
                        }
                        finishPromptEnhancement()
                    }
                }
                .onFailure { ex ->
                    runInEdt {
                        if (Disposer.isDisposed(parentDisposable)) return@runInEdt
                        finishPromptEnhancement()
                        OverlayUtil.showNotification(
                            ex.message ?: CodeGPTBundle.get("error.promptEnhancerFailed"),
                            NotificationType.ERROR
                        )
                    }
                }
        }
    }

    private fun finishPromptEnhancement() {
        isPromptEnhancing = false
        setThinkingVisible(false)
        updatePromptEnhancerState(promptTextField.getExpandedText())
    }

    private fun updatePromptEnhancerState(text: String) {
        promptEnhancerButton?.isEnabled = !isPromptEnhancing && text.isNotBlank()
    }

    private fun createToolbarSeparator(): JPanel {
        return JPanel().apply {
            isOpaque = true
            background = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            preferredSize = Dimension(1, 16)
            minimumSize = Dimension(1, 16)
            maximumSize = Dimension(1, 16)
        }
    }

    private fun createActionSeparator(height: Int = 16): JPanel {
        val scaledHeight = JBUI.scale(height)
        return JPanel().apply {
            isOpaque = true
            background = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            preferredSize = Dimension(1, scaledHeight)
            minimumSize = Dimension(1, scaledHeight)
            maximumSize = Dimension(1, scaledHeight)
        }
    }

    private fun createFooterPanel(featureType: FeatureType): JPanel {
        val currentService =
            ModelSelectionService.getInstance().getServiceForFeature(featureType)
        val availableProviders = ModelRegistry.getInstance().getProvidersForFeature(featureType)
        val modelComboBox = ModelComboBoxAction(
            project,
            { imageActionSupported.set(isImageActionSupported()) },
            currentService,
            availableProviders,
            true,
            featureType
        ).createCustomComponent(ActionPlaces.UNKNOWN).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        modelComboBoxComponent = modelComboBox

        val pnl = panel {
            twoColumnsRow(
                {
                    panel {
                        row {
                            cell(modelComboBox).gap(RightGap.SMALL)
                            if (agentTokenCounterPanel != null) {
                                cell(agentTokenCounterPanel).gap(RightGap.SMALL)
                            }
                            cell(thinkingPanel).gap(RightGap.SMALL)
                            cell(acceptChip).gap(RightGap.SMALL)
                            cell(rejectChip).gap(RightGap.SMALL)
                            if (featureType == FeatureType.INLINE_EDIT) {
                                cell(quickQuestionCheckbox)
                            }
                        }
                    }.align(AlignX.LEFT)
                },
                {
                    panel {
                        row {
                            if (applyChip != null) cell(applyChip).gap(RightGap.SMALL)
                            if (promptEnhancerButton != null && promptEnhancerSeparator != null) {
                                cell(promptEnhancerButton).gap(RightGap.SMALL)
                                cell(promptEnhancerSeparator).gap(RightGap.SMALL)
                            }
                            cell(submitButton).gap(RightGap.SMALL)
                            cell(stopButton)
                        }
                    }.align(AlignX.RIGHT)
                })
        }.andTransparent()
        return pnl
    }

    fun setInlineEditControlsVisible(visible: Boolean) {
        inlineEditControls.forEach { it.isVisible = visible }
        revalidate()
        repaint()
    }

    fun setApplyVisible(visible: Boolean) {
        userInputHeaderPanel.setApplyVisible(visible)
    }

    fun setApplyEnabled(enabled: Boolean) {
        userInputHeaderPanel.setApplyEnabled(enabled)
    }

    fun setThinkingVisible(visible: Boolean, text: String = CodeGPTBundle.get("shared.thinking")) {
        thinkingLabel.text = text
        thinkingIcon.isVisible = visible
        thinkingLabel.isVisible = visible
        thinkingPanel.isVisible = visible
        revalidate()
        repaint()
    }


    private fun isImageActionSupported(): Boolean {
        val currentModel = ModelSelectionService.getInstance().getModelForFeature(featureType)
        val currentService =
            ModelSelectionService.getInstance().getServiceForFeature(featureType)

        return when (currentService) {
            ServiceType.CUSTOM_OPENAI,
            ServiceType.ANTHROPIC,
            ServiceType.GOOGLE,
            ServiceType.OPENAI,
            ServiceType.OLLAMA -> true

            ServiceType.PROXYAI -> isCodeGPTModelSupported(currentModel)
            else -> false
        }
    }

    private fun isCodeGPTModelSupported(modelCode: String): Boolean {
        return modelCode in setOf(
            ModelRegistry.GPT_4_1,
            ModelRegistry.GPT_4_1_MINI,
            ModelRegistry.GEMINI_PRO_2_5,
            ModelRegistry.GEMINI_FLASH_2_5,
            ModelRegistry.CLAUDE_4_5_SONNET,
            ModelRegistry.CLAUDE_4_5_SONNET_THINKING
        )
    }

    private fun updatePreferredSizeFromChildren() {
        val headerHeight = userInputHeaderPanel.preferredSize?.height ?: 0
        val textFieldHeight = promptTextField.preferredSize?.height ?: 0
        val footerHeight = footerPanelRef?.preferredSize?.height ?: 0
        val desiredHeight =
            headerHeight + textFieldHeight + footerHeight + insets.top + insets.bottom

        val oldSize = preferredSize
        val newSize = Dimension(JBUI.scale(600), desiredHeight.coerceAtLeast(JBUI.scale(80)))
        if (oldSize != newSize) {
            preferredSize = newSize
            revalidate()
            repaint()
            firePropertyChange("preferredSize", oldSize, newSize)
        }
    }
}
