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
import com.intellij.ui.components.AnActionLink
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.actions.AttachImageAction
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.ModelComboBoxAction
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.TotalTokensPanel
import ee.carlrobert.codegpt.ui.IconActionButton
import ee.carlrobert.codegpt.ui.textarea.header.UserInputHeaderPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupActionItem
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel
import git4idea.GitCommit
import java.awt.*
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

class UserInputPanel(
    private val project: Project,
    private val conversation: Conversation,
    private val totalTokensPanel: TotalTokensPanel,
    parentDisposable: Disposable,
    private val tagManager: TagManager,
    private val onSubmit: (String) -> Unit,
    private val onStop: () -> Unit
) : JPanel(BorderLayout()) {

    companion object {
        private const val CORNER_RADIUS = 16
    }

    private val disposableCoroutineScope = DisposableCoroutineScope()
    private val promptTextField =
        PromptTextField(
            project,
            tagManager,
            ::updateUserTokens,
            ::handleBackSpace,
            ::handleLookupAdded,
            ::handleSubmit
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

    init {
        Disposer.register(parentDisposable, disposableCoroutineScope)
        background = service<EditorColorsManager>().globalScheme.defaultBackground
        add(userInputHeaderPanel, BorderLayout.NORTH)
        add(promptTextField, BorderLayout.CENTER)
        add(getFooter(), BorderLayout.SOUTH)

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

        Disposer.register(parentDisposable, promptTextField)
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
            if (promptTextField.text.isEmpty()) {
                promptTextField.text = if (gitCommits.size == 1) {
                    "Explain the commit `${gitCommits[0].id.toShortString()}`"
                } else {
                    "Explain the commits ${gitCommits.joinToString(", ") { "`${it.id.toShortString()}`" }}"
                }
            }

            gitCommits.forEach {
                addTag(GitCommitTagDetails(it))
            }
            promptTextField.requestFocusInWindow()
            promptTextField.editor?.caretModel?.moveToOffset(promptTextField.text.length)
        }
    }

    fun addTag(tagDetails: TagDetails) {
        userInputHeaderPanel.addTag(tagDetails)
        val text = promptTextField.text
        if (text.isNotEmpty() && text.last() == '@') {
            promptTextField.text = text.substring(0, text.length - 1)
        }
    }

    fun includeFiles(referencedFiles: MutableList<VirtualFile>) {
        referencedFiles.forEach { userInputHeaderPanel.addTag(FileTagDetails(it)) }
    }

    override fun requestFocus() {
        invokeLater {
            promptTextField.requestFocusInWindow()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val area = Area(Rectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat()))
        val roundedRect = RoundRectangle2D.Float(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            CORNER_RADIUS.toFloat(),
            CORNER_RADIUS.toFloat()
        )
        area.intersect(Area(roundedRect))

        g2.clip = area

        g2.color = background
        g2.fill(area)

        super.paintComponent(g2)
        g2.dispose()
    }

    override fun paintBorder(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBUI.CurrentTheme.Focus.defaultButtonColor()
        if (promptTextField.isFocusOwner) {
            g2.stroke = BasicStroke(1.5F)
        }
        g2.drawRoundRect(0, 0, width - 1, height - 1, CORNER_RADIUS, CORNER_RADIUS)
        g2.dispose()
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

    private fun getFooter(): JPanel {
        val attachImageLink = AnActionLink(CodeGPTBundle.get("shared.image"), AttachImageAction())
            .apply {
                icon = AllIcons.FileTypes.Image
                font = JBUI.Fonts.smallFont()
            }
        val modelComboBox = ModelComboBoxAction(
            project,
            {
                imageActionSupported.set(isImageActionSupported())
                // TODO: Implement a proper session management
                val conversationService = service<ConversationService>()
                if (conversation.messages.isNotEmpty()) {
                    conversationService.startConversation()
                    project.service<ChatToolWindowContentManager>().createNewTabPanel()
                } else {
                    conversation.model = conversationService.getModelForSelectedService(it)
                }
            },
            service<GeneralSettings>().state.selectedService
        ).createCustomComponent(ActionPlaces.UNKNOWN)

        return panel {
            twoColumnsRow({
                cell(modelComboBox).gap(RightGap.SMALL)
                cell(attachImageLink).visibleIf(imageActionSupported)
            }, {
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
        return when (service<GeneralSettings>().state.selectedService) {
            ServiceType.CUSTOM_OPENAI,
            ServiceType.ANTHROPIC,
            ServiceType.AZURE,
            ServiceType.OLLAMA -> true

            ServiceType.CODEGPT -> {
                listOf(
                    "gpt-4o",
                    "gpt-4o-mini",
                    "gemini-pro-1.5",
                    "claude-3-opus",
                    "claude-3.5-sonnet"
                ).contains(
                    service<CodeGPTServiceSettings>()
                        .state
                        .chatCompletionSettings
                        .model
                )
            }

            ServiceType.OPENAI -> {
                listOf(
                    OpenAIChatCompletionModel.GPT_4_VISION_PREVIEW.code,
                    OpenAIChatCompletionModel.GPT_4_O.code,
                    OpenAIChatCompletionModel.GPT_4_O_MINI.code
                ).contains(service<OpenAISettings>().state.model)
            }

            else -> false
        }
    }
}