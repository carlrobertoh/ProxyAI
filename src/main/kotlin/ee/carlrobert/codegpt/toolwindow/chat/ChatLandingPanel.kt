package ee.carlrobert.codegpt.toolwindow.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.completions.CompletionRequestUtil
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.psistructure.PsiStructureProvider
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.toolwindow.ToolWindowInitialState
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureState
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatToolWindowScrollablePanel
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.TotalTokensPanel
import ee.carlrobert.codegpt.toolwindow.ui.ChatToolWindowLandingPanel
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.ui.UIUtil.createScrollPaneWithSmartScroller
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.coroutines.CoroutineDispatchers
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel

class ChatLandingPanel(
    private val project: Project,
    private val onSubmitInitialMessage: InitialMessageSubmitHandler
) : BorderLayoutPanel(), Disposable {

    private val tagManager = TagManager()
    private val toolWindowScrollablePanel = ChatToolWindowScrollablePanel()
    private val psiStructureRepository = PsiStructureRepository(
        this,
        project,
        tagManager,
        PsiStructureProvider(),
        CoroutineDispatchers()
    )
    private val totalTokensPanel = TotalTokensPanel(
        Conversation(),
        EditorUtil.getSelectedEditorSelectedText(project),
        this,
        psiStructureRepository
    )
    private val userInputPanel = UserInputPanel(
        project,
        totalTokensPanel,
        this,
        FeatureType.CHAT,
        tagManager,
        this::handleSubmit,
        this::handleCancel,
        true
    )

    init {
        addToCenter(createScrollPaneWithSmartScroller(toolWindowScrollablePanel))
        addToBottom(createSouthPanel())
        toolWindowScrollablePanel.displayLandingView(getLandingView())
        userInputPanel.requestFocus()
    }

    fun requestFocusForTextArea() {
        userInputPanel.requestFocus()
    }

    override fun dispose() = Unit

    private fun handleSubmit(text: String) {
        submitInitialMessage(text)
    }

    private fun handleCancel(): Unit = Unit

    private fun submitInitialMessage(text: String) {
        if (text.isBlank()) {
            return
        }

        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val selectedTags = userInputPanel.getSelectedTags()
            val conversation = ConversationService.getInstance().startConversation(project)
            val initialState =
                ToolWindowInitialState(conversation, selectedTags, userInputPanel.getChatMode())
            val message = ChatContextSupport.buildMessage(project, text, selectedTags)
            val psiStructure = currentPsiStructure()

            application.invokeLater {
                onSubmitInitialMessage.submitInitialMessage(message, psiStructure, initialState)
            }
        }
    }

    private fun currentPsiStructure(): Set<ClassStructure> {
        return when (val structureState = psiStructureRepository.structureState.value) {
            is PsiStructureState.Content -> structureState.elements
            else -> emptySet()
        }
    }

    private fun createSouthPanel(): JComponent {
        return BorderLayoutPanel()
            .addToTop(createStatusPanel())
            .addToCenter(createUserPromptPanel())
    }

    private fun createStatusPanel(): JComponent {
        val statusPanel = JPanel(GridBagLayout())
        statusPanel.border = JBUI.Borders.empty(8)
        statusPanel.isOpaque = false

        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        statusPanel.add(Box.createHorizontalGlue(), gbc)

        gbc.gridx = 1
        gbc.weightx = 0.0
        gbc.anchor = GridBagConstraints.EAST
        gbc.fill = GridBagConstraints.NONE
        statusPanel.add(totalTokensPanel, gbc)
        return statusPanel
    }

    private fun createUserPromptPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(8)
            )
            add(userInputPanel, BorderLayout.CENTER)
        }
    }

    private fun getLandingView(): JComponent {
        return ChatToolWindowLandingPanel { action, locationOnScreen ->
            val editor = EditorUtil.getSelectedEditor(project)
            if (editor == null || !editor.selectionModel.hasSelection()) {
                OverlayUtil.showWarningBalloon(
                    if (editor == null) {
                        "Unable to locate a selected editor"
                    } else {
                        "Please select a target code before proceeding"
                    },
                    locationOnScreen
                )
                return@ChatToolWindowLandingPanel
            }

            val selectedText =
                editor.selectionModel.selectedText ?: return@ChatToolWindowLandingPanel
            val formattedCode = CompletionRequestUtil.formatCode(
                selectedText,
                editor.virtualFile.path
            )
            submitInitialMessage(action.prompt.replace("{SELECTION}", formattedCode))
        }
    }
}
