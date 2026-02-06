package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.agent.AgentService
import ee.carlrobert.codegpt.agent.AgentToolOutputNotifier
import ee.carlrobert.codegpt.agent.MessageWithContext
import ee.carlrobert.codegpt.agent.ToolSpecs
import ee.carlrobert.codegpt.agent.ToolRunContext
import ee.carlrobert.codegpt.agent.rollback.RollbackService
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.conversations.message.QueuedMessage
import ee.carlrobert.codegpt.psistructure.PsiStructureProvider
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.toolwindow.agent.ui.AgentToolWindowLandingPanel
import ee.carlrobert.codegpt.toolwindow.agent.ui.RollbackPanel
import ee.carlrobert.codegpt.toolwindow.agent.ui.TodoListPanel
import ee.carlrobert.codegpt.toolwindow.agent.ui.ToolCallCard
import ee.carlrobert.codegpt.toolwindow.chat.MessageBuilder
import ee.carlrobert.codegpt.toolwindow.chat.editor.actions.CopyAction
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatMessageResponseBody
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatToolWindowScrollablePanel
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.TotalTokensPanel
import ee.carlrobert.codegpt.toolwindow.ui.ResponseMessagePanel
import ee.carlrobert.codegpt.toolwindow.ui.UserMessagePanel
import ee.carlrobert.codegpt.ui.UIUtil.createScrollPaneWithSmartScroller
import ee.carlrobert.codegpt.ui.components.TokenUsageCounterPanel
import ee.carlrobert.codegpt.ui.queue.QueuedMessagePanel
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.coroutines.CoroutineDispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class AgentToolWindowTabPanel(
    private val project: Project,
    private val agentSession: AgentSession
) : BorderLayoutPanel(), Disposable {
    private val replayJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val scrollablePanel = ChatToolWindowScrollablePanel()
    private val tagManager = TagManager()
    private val dispatchers = CoroutineDispatchers()
    private val sessionId = agentSession.sessionId
    private val conversation = agentSession.conversation
    private val psiRepository = PsiStructureRepository(
        this,
        project,
        tagManager,
        PsiStructureProvider(),
        dispatchers
    )

    private val approvalContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 0, 4, 0)
        isVisible = false
    }

    private val queuedMessageContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 0, 4, 0)
        isVisible = false
    }

    private val loadingLabel =
        JBLabel(
            CodeGPTBundle.get("toolwindow.chat.loading"),
            AnimatedIcon.Default(),
            JBLabel.LEFT
        ).apply {
            isVisible = false
        }

    private val userInputPanel = UserInputPanel(
        project,
        TotalTokensPanel(
            conversation,
            EditorUtil.getSelectedEditorSelectedText(project),
            this,
            psiRepository
        ),
        this,
        FeatureType.AGENT,
        tagManager,
        onSubmit = { text -> handleSubmit(text) },
        onStop = { handleCancel() },
        withRemovableSelectedEditorTag = true,
        agentTokenCounterPanel = TokenUsageCounterPanel(project, sessionId),
        sessionIdProvider = { sessionId },
        conversationIdProvider = { conversation.id }
    )
    private var rollbackPanel: RollbackPanel
    private val todoListPanel = TodoListPanel()
    private val projectMessageBusConnection = project.messageBus.connect()
    private val appMessageBusConnection = ApplicationManager.getApplication().messageBus.connect()
    private val rollbackService = RollbackService.getInstance(project)

    private val eventHandler = AgentEventHandler(
        project = project,
        sessionId = sessionId,
        agentApprovalManager = AgentApprovalManager(project),
        approvalContainer = approvalContainer,
        scrollablePanel = scrollablePanel,
        todoListPanel = todoListPanel,
        userInputPanel = userInputPanel,
        onShowLoading = { text ->
            loadingLabel.text = text
            loadingLabel.isVisible = true
            revalidate()
            repaint()
        },
        onHideLoading = {
            loadingLabel.isVisible = false
            revalidate()
            repaint()
            rollbackPanel.refreshOperations()
        },
        onQueuedMessagesResolved = { message ->
            runInEdt {
                clearQueuedMessagesAndCreateNewResponse(
                    message.uiText
                )
            }
        }
    )

    init {
        setupMessageBusSubscriptions()
        rollbackPanel = RollbackPanel(project, sessionId) {
            rollbackPanel.refreshOperations()
        }
        setupUI()

        if (conversation.messages.isEmpty()) {
            displayLandingView()
        } else {
            displayRecoveredConversation()
        }

        userInputPanel.setStopEnabled(false)
        Disposer.register(this, eventHandler)
    }

    private fun setupMessageBusSubscriptions() {
        project.service<AgentService>().queuedMessageProcessed.let { flow ->
            kotlinx.coroutines.CoroutineScope(dispatchers.io()).launch {
                flow.collect { processedMessage ->
                    ApplicationManager.getApplication().invokeLater {
                        removeQueuedMessage(processedMessage)
                    }
                }
            }
        }

        appMessageBusConnection.subscribe(
            AgentToolOutputNotifier.AGENT_TOOL_OUTPUT_TOPIC,
            object : AgentToolOutputNotifier {
                override fun toolOutput(toolId: String, text: String, isError: Boolean) {
                    val namespacedToolId = "${sessionId}:${toolId}"
                    eventHandler.handleToolOutput(namespacedToolId, text, isError)
                }
            }
        )
    }

    private fun setupUI() {
        addToCenter(createScrollPaneWithSmartScroller(scrollablePanel))
        addToBottom(createUserPromptPanel())
    }

    private fun createUserPromptPanel(): JComponent {
        val topContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        rollbackPanel.alignmentX = LEFT_ALIGNMENT
        topContainer.add(rollbackPanel)

        todoListPanel.alignmentX = LEFT_ALIGNMENT
        topContainer.add(todoListPanel)

        queuedMessageContainer.alignmentX = LEFT_ALIGNMENT
        topContainer.add(queuedMessageContainer)

        approvalContainer.alignmentX = LEFT_ALIGNMENT
        topContainer.add(approvalContainer)

        val loadingContainer =
            BorderLayoutPanel().withBorder(JBUI.Borders.empty(8)).addToCenter(loadingLabel)

        return BorderLayoutPanel()
            .addToTop(loadingContainer)
            .addToCenter(
                BorderLayoutPanel().withBorder(
                    JBUI.Borders.compound(
                        JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                        JBUI.Borders.empty(8)
                    )
                )
                    .addToTop(topContainer)
                    .addToCenter(userInputPanel)
            )
    }

    private fun handleSubmit(text: String) {
        if (text.isBlank()) return
        scrollablePanel.clearLandingViewIfVisible()
        agentSession.serviceType =
            ModelSelectionService.getInstance().getServiceForFeature(FeatureType.AGENT)

        val agentService = project.service<AgentService>()

        if (agentService.isSessionRunning(sessionId)) {
            addQueuedMessage(text)
            userInputPanel.clearText()
            userInputPanel.setSubmitEnabled(true)
            userInputPanel.setStopEnabled(true)

            agentService.submitMessage(
                MessageWithContext(text, userInputPanel.getSelectedTags()),
                eventHandler,
                sessionId
            )
            return
        }

        runCatching {
            project.service<AgentToolWindowContentManager>()
                .setTabStatus(sessionId, AgentToolWindowTabbedPane.TabStatus.RUNNING)
        }

        rollbackService.startSession(sessionId)
        rollbackPanel.refreshOperations()

        val message = MessageWithContext(text, userInputPanel.getSelectedTags())
        val messagePanel = scrollablePanel.addMessage(message.id)
        val userPanel = UserMessagePanel(
            project,
            MessageBuilder(project, text).withTags(userInputPanel.getSelectedTags()).build(),
            this
        )
        val responsePanel = ResponseMessagePanel()
        val responseBody = ChatMessageResponseBody(
            project,
            false,
            false,
            false,
            false,
            true,
            this
        )

        responsePanel.setResponseContent(responseBody)
        userPanel.addCopyAction { CopyAction.copyToClipboard(text) }
        messagePanel.add(userPanel)
        messagePanel.add(responsePanel)
        scrollablePanel.update()

        eventHandler.resetForNewSubmission()
        eventHandler.setCurrentResponseBody(responseBody)

        loadingLabel.text = CodeGPTBundle.get("toolwindow.chat.loading")
        loadingLabel.isVisible = true

        clearQueuedMessages()
        agentService.clearPendingMessages(sessionId)
        userInputPanel.setStopEnabled(true)

        agentService.submitMessage(message, eventHandler, sessionId)
    }

    private fun handleCancel() {
        val agentService = project.service<AgentService>()
        agentService.cancelCurrentRun(sessionId)
        agentService.clearPendingMessages(sessionId)

        rollbackService.finishSession(sessionId)
        rollbackPanel.refreshOperations()

        approvalContainer.removeAll()
        clearQueuedMessages()
        approvalContainer.isVisible = false
        loadingLabel.isVisible = false
        userInputPanel.setStopEnabled(false)

        runCatching {
            project.service<AgentToolWindowContentManager>()
                .setTabStatus(sessionId, AgentToolWindowTabbedPane.TabStatus.STOPPED)
        }
    }

    private fun displayLandingView() {
        scrollablePanel.displayLandingView(createLandingView())
    }

    private fun displayRecoveredConversation() {
        scrollablePanel.clearAll()
        conversation.messages.forEach { message ->
            val prompt = message.prompt.orEmpty()
            val wrapper = scrollablePanel.addMessage(message.id)
            val userPanel = UserMessagePanel(project, message, this)
            userPanel.addCopyAction { CopyAction.copyToClipboard(prompt) }
            wrapper.add(userPanel)

            val responseBody = ChatMessageResponseBody(
                project,
                false,
                false,
                false,
                false,
                false,
                this
            )
            addRecoveredToolCards(responseBody, message)
            responseBody.withResponse(message.response.orEmpty())
            val responsePanel = ResponseMessagePanel().apply {
                setResponseContent(responseBody)
            }
            wrapper.add(responsePanel)
        }

        scrollablePanel.update()
        scrollablePanel.scrollToBottom()
    }

    private fun addRecoveredToolCards(responseBody: ChatMessageResponseBody, message: Message) {
        val toolCalls = message.toolCalls ?: return
        val toolCallResults = message.toolCallResults ?: emptyMap()

        toolCalls.forEach { toolCall ->
            val toolName = toolCall.function.name ?: return@forEach
            if (toolName == "TodoWrite") {
                return@forEach
            }

            val rawArgs = toolCall.function.arguments.orEmpty()
            val args = parseRecoveredToolArgs(toolName, rawArgs)
            val card = createRecoveredToolCard(toolName, args, rawArgs)
            responseBody.addToolStatusPanel(card)

            val rawResult = toolCallResults[toolCall.id] ?: return@forEach
            val parsedResult = parseRecoveredToolResult(toolName, rawResult)
            val success = inferRecoveredToolSuccess(parsedResult, rawResult)
            card.complete(success, parsedResult ?: rawResult)
        }
    }

    private fun createRecoveredToolCard(
        toolName: String,
        args: Any,
        rawArgs: String
    ): ToolCallCard {
        return try {
            ToolCallCard(project, toolName, args)
        } catch (_: Exception) {
            val fallbackName = "Recovered $toolName"
            val fallbackArgs = rawArgs.ifBlank { "(no arguments)" }
            ToolCallCard(project, fallbackName, fallbackArgs)
        }
    }

    private fun parseRecoveredToolArgs(toolName: String, rawArgs: String): Any {
        val payload = rawArgs.trim()
        if (payload.isBlank()) return ""

        return ToolSpecs.decodeArgsOrNull(
            json = replayJson,
            toolName = toolName,
            payload = payload
        ) ?: payload
    }

    private fun parseRecoveredToolResult(toolName: String, rawResult: String): Any? {
        val payload = rawResult.trim()
        if (payload.isBlank()) return null

        return ToolSpecs.decodeResultOrNull(
            json = replayJson,
            toolName = toolName,
            payload = payload
        ) ?: payload
    }

    private fun inferRecoveredToolSuccess(parsedResult: Any?, rawResult: String): Boolean {
        if (parsedResult != null) {
            return parsedResult::class.simpleName != "Error"
        }
        return !rawResult.contains("failed", ignoreCase = true) &&
                !rawResult.contains("error", ignoreCase = true)
    }

    private fun createLandingView(): AgentToolWindowLandingPanel {
        return AgentToolWindowLandingPanel(project)
    }

    fun getSessionId(): String = sessionId

    fun getAgentSession(): AgentSession = agentSession

    fun getConversation(): Conversation = conversation

    fun requestFocusForTextArea() {
        userInputPanel.requestFocus()
    }

    fun addQueuedMessage(message: String) {
        val queuedMessage = QueuedMessage(message)
        val existingPanel = getQueuedMessagePanel()
        if (existingPanel != null) {
            val messages = existingPanel.getQueuedMessages().toMutableList()
            messages.add(queuedMessage)
            val updatedPanel = QueuedMessagePanel(messages)
            val index = queuedMessageContainer.components.indexOf(existingPanel)
            if (index >= 0) {
                queuedMessageContainer.remove(existingPanel)
                queuedMessageContainer.add(updatedPanel, index)
            }
        } else {
            val queuedPanel = QueuedMessagePanel(listOf(queuedMessage))
            if (queuedMessageContainer.componentCount > 0) {
                queuedMessageContainer.add(Box.createVerticalStrut(4))
            }

            queuedPanel.alignmentX = LEFT_ALIGNMENT
            queuedMessageContainer.add(queuedPanel)
        }

        queuedMessageContainer.isVisible = true
        queuedMessageContainer.revalidate()
        queuedMessageContainer.repaint()
    }

    private fun getQueuedMessagePanel(): QueuedMessagePanel? {
        return queuedMessageContainer.components
            .filterIsInstance<QueuedMessagePanel>()
            .firstOrNull()
    }

    fun clearQueuedMessages() {
        queuedMessageContainer.removeAll()
        queuedMessageContainer.isVisible = false
        queuedMessageContainer.revalidate()
        queuedMessageContainer.repaint()
    }

    private fun clearQueuedMessagesAndCreateNewResponse(messageText: String) {
        clearQueuedMessages()

        val message = Message(messageText)
        val messagePanel = scrollablePanel.addMessage(message.id)
        val userPanel = UserMessagePanel(project, message, this)
        userPanel.addCopyAction { CopyAction.copyToClipboard(message.prompt) }
        messagePanel.add(userPanel)

        val responseBody = ChatMessageResponseBody(project, false, false, false, false, true, this)
        val responsePanel = ResponseMessagePanel()
        responsePanel.setResponseContent(responseBody)
        messagePanel.add(responsePanel)

        eventHandler.resetForNewSubmission()
        eventHandler.setCurrentResponseBody(responseBody)

        scrollablePanel.update()
    }

    fun removeQueuedMessage(messageText: String) {
        val panel = getQueuedMessagePanel() ?: return

        val messages = panel.getQueuedMessages().toMutableList()
        val messageToRemove = messages.find { it.prompt == messageText }

        if (messageToRemove != null) {
            messages.remove(messageToRemove)

            if (messages.isEmpty()) {
                queuedMessageContainer.remove(panel)
            } else {
                val updatedPanel = QueuedMessagePanel(messages)
                val index = queuedMessageContainer.components.indexOf(panel)
                if (index >= 0) {
                    queuedMessageContainer.remove(panel)
                    queuedMessageContainer.add(updatedPanel, index)
                }
            }

            queuedMessageContainer.revalidate()
            queuedMessageContainer.repaint()

            if (queuedMessageContainer.componentCount == 0) {
                queuedMessageContainer.isVisible = false
            }
        }
    }

    override fun dispose() {
        ToolRunContext.cleanupSession(sessionId)

        projectMessageBusConnection.disconnect()
        appMessageBusConnection.disconnect()
    }
}
