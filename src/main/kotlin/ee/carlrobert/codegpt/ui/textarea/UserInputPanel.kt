package ee.carlrobert.codegpt.ui.textarea

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.settings.configuration.ChatMode
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.models.ModelRegistry
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.ModelComboBoxAction
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.TotalTokensPanel
import ee.carlrobert.codegpt.ui.IconActionButton
import ee.carlrobert.codegpt.ui.dnd.FileDragAndDrop
import ee.carlrobert.codegpt.ui.textarea.header.UserInputHeaderPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import git4idea.GitCommit
import java.awt.*
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

class UserInputPanel(
    private val project: Project,
    private val totalTokensPanel: TotalTokensPanel,
    parentDisposable: Disposable,
    private val tagManager: TagManager,
    private val onSubmit: (String) -> Unit,
    private val onStop: () -> Unit
) : JPanel(BorderLayout()) {

    companion object {
        private const val CORNER_RADIUS = 16
    }

    private var chatMode: ChatMode = ConfigurationSettings.getState().chatMode
    private val disposableCoroutineScope = DisposableCoroutineScope()
    private val promptTextField =
        PromptTextField(
            project,
            tagManager,
            ::updateUserTokens,
            ::handleBackSpace,
            ::handleLookupAdded,
            ::handleSubmit,
            onFilesDropped = { files ->
                includeFiles(files.toMutableList())
                totalTokensPanel.updateReferencedFilesTokens(files.map { ReferencedFile.from(it).fileContent() })
            }
        )
    private val userInputHeaderPanel =
        UserInputHeaderPanel(
            project,
            tagManager,
            totalTokensPanel,
            promptTextField
        )
    private val submitButton = IconActionButton(
        object : AnAction(
            CodeGPTBundle.get("smartTextPane.submitButton.title"),
            CodeGPTBundle.get("smartTextPane.submitButton.description"),
            IconUtil.scale(Icons.Send, null, 0.85f)
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                handleSubmit(promptTextField.text)
            }
        },
        "SUBMIT"
    )
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
    ).apply { isEnabled = false }
    private val imageActionSupported = AtomicBooleanProperty(isImageActionSupported())

    val text: String
        get() = promptTextField.text

    fun getChatMode(): ChatMode = chatMode

    fun setChatMode(mode: ChatMode) {
        chatMode = mode
        ConfigurationSettings.getState().chatMode = mode
    }

    init {
        setupDisposables(parentDisposable)
        setupLayout()
        addSelectedEditorContent()
        FileDragAndDrop.install(this) { files ->
            includeFiles(files.toMutableList())
            totalTokensPanel.updateReferencedFilesTokens(files.map { ReferencedFile.from(it).fileContent() })
        }
    }

    private fun setupDisposables(parentDisposable: Disposable) {
        Disposer.register(parentDisposable, disposableCoroutineScope)
        Disposer.register(parentDisposable, promptTextField)
    }

    private fun setupLayout() {
        background = service<EditorColorsManager>().globalScheme.defaultBackground
        add(userInputHeaderPanel, BorderLayout.NORTH)
        add(promptTextField, BorderLayout.CENTER)
        add(createFooterPanel(), BorderLayout.SOUTH)
    }

    private fun addSelectedEditorContent() {
        EditorUtil.getSelectedEditor(project)?.let { editor ->
            if (EditorUtil.hasSelection(editor)) {
                tagManager.addTag(
                    EditorSelectionTagDetails(
                        editor.virtualFile,
                        editor.selectionModel
                    )
                )
            }
        }
    }

    fun getSelectedTags(): List<TagDetails> {
        return userInputHeaderPanel.getSelectedTags()
    }

    fun setSubmitEnabled(enabled: Boolean) {
        submitButton.isEnabled = enabled
        stopButton.isEnabled = !enabled
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
        totalTokensPanel.updateUserPromptTokens(text)
    }

    private fun handleBackSpace() {
        if (text.isEmpty()) {
            userInputHeaderPanel.getLastTag()?.let { tagManager.remove(it) }
        }
    }

    private fun handleLookupAdded(item: LookupActionItem) {
        item.execute(project, this)
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

    private fun createFooterPanel(): JPanel {
        val currentService = ModelSelectionService.getInstance().getServiceForFeature(FeatureType.CHAT)
        val modelComboBox = ModelComboBoxAction(
            project,
            { imageActionSupported.set(isImageActionSupported()) },
            currentService
        ).createCustomComponent(ActionPlaces.UNKNOWN)

        val searchReplaceToggle =
            SearchReplaceToggleAction(this).createCustomComponent(ActionPlaces.UNKNOWN)

        return panel {
            twoColumnsRow(
                {
                    panel {
                        row {
                            cell(modelComboBox).gap(RightGap.SMALL)
                            cell(createToolbarSeparator()).gap(RightGap.SMALL)
                            cell(searchReplaceToggle)
                        }
                    }.align(AlignX.LEFT)
                },
                {
                    panel {
                        row {
                            cell(submitButton).gap(RightGap.SMALL)
                            cell(stopButton)
                        }
                    }.align(AlignX.RIGHT)
                })
        }.andTransparent()
    }

    private fun isImageActionSupported(): Boolean {
        val currentModel = ModelSelectionService.getInstance().getModelForFeature(FeatureType.CHAT)
        val currentService = ModelSelectionService.getInstance().getServiceForFeature(FeatureType.CHAT)
        
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
            ModelRegistry.CLAUDE_4_SONNET,
            ModelRegistry.CLAUDE_4_SONNET_THINKING
        )
    }
}
