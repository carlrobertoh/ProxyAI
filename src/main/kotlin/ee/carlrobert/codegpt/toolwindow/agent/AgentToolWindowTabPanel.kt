package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.agent.*
import ee.carlrobert.codegpt.agent.ProxyAIAgent.loadProjectInstructions
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgentService
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgents
import ee.carlrobert.codegpt.agent.history.AgentCheckpointHistoryService
import ee.carlrobert.codegpt.agent.history.AgentCheckpointTurnSequencer
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.agent.rollback.RollbackService
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.conversations.message.QueuedMessage
import ee.carlrobert.codegpt.mcp.McpTagStatusUpdater
import ee.carlrobert.codegpt.psistructure.PsiStructureProvider
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.toolwindow.ToolWindowInitialState
import ee.carlrobert.codegpt.toolwindow.agent.ui.*
import ee.carlrobert.codegpt.toolwindow.chat.MessageBuilder
import ee.carlrobert.codegpt.toolwindow.chat.editor.actions.CopyAction
import ee.carlrobert.codegpt.toolwindow.chat.structure.data.PsiStructureRepository
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatMessageResponseBody
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatToolWindowScrollablePanel
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.TotalTokensPanel
import ee.carlrobert.codegpt.toolwindow.ui.ResponseMessagePanel
import ee.carlrobert.codegpt.toolwindow.ui.UserMessagePanel
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.ui.UIUtil.createScrollPaneWithSmartScroller
import ee.carlrobert.codegpt.ui.components.TokenUsageCounterPanel
import ee.carlrobert.codegpt.ui.queue.QueuedMessagePanel
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.StringUtil.stripThinkingBlocks
import ee.carlrobert.codegpt.util.coroutines.CoroutineDispatchers
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import kotlinx.coroutines.*
import java.util.*
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import ai.koog.prompt.message.Message as PromptMessage

class AgentToolWindowTabPanel(
    private val project: Project,
    private val agentSession: AgentSession,
    private val initialMessageSubmitHandler: ((MessageWithContext) -> Unit)? = null
) : BorderLayoutPanel(), Disposable {
    companion object {
        private const val RECOVERED_CONVERSATION_RENDER_BATCH_SIZE = 6
    }

    private val scrollablePanel = ChatToolWindowScrollablePanel()
    private val tagManager = TagManager()
    private val dispatchers = CoroutineDispatchers()
    private val backgroundScope = DisposableCoroutineScope(dispatchers.io())
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
    private val tokenUsageCounterPanel = TokenUsageCounterPanel(project, sessionId)

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
        onSubmit = ::handleSubmit,
        onStop = ::handleCancel,
        withRemovableSelectedEditorTag = true,
        agentTokenCounterPanel = tokenUsageCounterPanel,
        agentTokenCounterVisibilityProvider = { tokenUsageCounterPanel.hasReportedUsage() },
        sessionIdProvider = { sessionId },
        conversationIdProvider = { conversation.id },
        onStartSessionTimeline = ::showSessionStartTimelineDialog,
        modelSelectorComponentFactory = ::createAgentModelSelector,
        secondaryFooterComponentFactory = ::createAgentRuntimeOptionsSelector,
        secondaryFooterComponentVisibilityProvider = { !agentSession.externalAgentId.isNullOrBlank() },
        promptEnhancerVisibilityProvider = { agentSession.externalAgentId.isNullOrBlank() },
        sessionTimelineVisibilityProvider = { agentSession.externalAgentId.isNullOrBlank() }
    )
    private var rollbackPanel: RollbackPanel
    private val todoListPanel = TodoListPanel()
    private val projectMessageBusConnection = project.messageBus.connect()
    private val appMessageBusConnection = ApplicationManager.getApplication().messageBus.connect()
    private val rollbackService = RollbackService.getInstance(project)
    private val historyService = project.service<AgentCheckpointHistoryService>()

    private data class RunCardState(
        val runMessageId: UUID,
        val rollbackRunId: String,
        var responsePanel: ResponseMessagePanel,
        var sourceMessage: Message? = null,
        var completed: Boolean = false
    )

    // Insertion order matters: timeline run numbering depends on the message order.
    private val runCardsByMessageId = linkedMapOf<UUID, RunCardState>()
    private var activeRunMessageId: UUID? = null
    private var recoveredConversationJob: Job? = null
    private var activeLandingPanel: AgentToolWindowLandingPanel? = null

    private val timelineController = AgentSessionTimelineController(
        project = project,
        agentSession = agentSession,
        conversation = conversation,
        runStateForRunIndex = ::runStateForRunIndex,
        applySeededSessionState = ::applySeededSessionState,
        onAfterRollbackRefresh = ::refreshViewAfterRollback
    )

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
        onRunFinishedCallback = {
            markActiveRunCompleted()
        },
        onRunCheckpointUpdatedCallback = { runMessageId, ref ->
            updateRunCheckpoint(runMessageId, ref)
        },
        onQueuedMessagePromoted = { message ->
            runInEdt {
                promoteQueuedMessageToActiveRun(message)
            }
        }
    )

    init {
        project.service<McpTagStatusUpdater>().registerTagManager(conversation.id, tagManager)
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
        Disposer.register(this, rollbackPanel)
        Disposer.register(this, eventHandler)
        Disposer.register(this, timelineController)
        Disposer.register(this, backgroundScope)
    }

    private fun setupMessageBusSubscriptions() {
        project.service<AgentService>().queuedMessageProcessed.let { flow ->
            backgroundScope.launch {
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
        val message = MessageWithContext(text, userInputPanel.getSelectedTags())
        if (initialMessageSubmitHandler != null) {
            initialMessageSubmitHandler.invoke(message)
            return
        }
        submitMessage(message)
    }

    fun submitMessage(message: MessageWithContext) {
        if (message.text.isBlank()) return
        clearLandingView()

        service<ModelSettings>().getModelSelectionForFeature(FeatureType.AGENT).let {
            agentSession.serviceType = it.provider
            agentSession.modelCode = it.selectionId
        }

        val agentService = project.service<AgentService>()
        if (agentService.isSessionRunning(sessionId)) {
            addQueuedMessage(message.text)
            userInputPanel.clearText()
            userInputPanel.setSubmitEnabled(true)
            userInputPanel.setStopEnabled(true)

            agentService.submitMessage(message, eventHandler, sessionId)
            return
        }

        runCatching {
            project.service<AgentToolWindowContentManager>()
                .setTabStatus(sessionId, AgentToolWindowTabbedPane.TabStatus.RUNNING)
        }

        val rollbackRunId = rollbackService.startSession(sessionId)
        rollbackPanel.refreshOperations()

        val messagePanel = scrollablePanel.addMessage(message.id)
        val userPanel = UserMessagePanel(
            project,
            MessageBuilder(project, message.text).withTags(message.tags).build(),
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
        userPanel.addCopyAction { CopyAction.copyToClipboard(message.text) }
        messagePanel.add(userPanel)
        messagePanel.add(responsePanel)
        scrollablePanel.update()

        registerRunCard(
            runMessageId = message.id,
            rollbackRunId = rollbackRunId,
            responsePanel = responsePanel,
            prompt = message.text
        )

        eventHandler.resetForNewSubmission()
        eventHandler.setCurrentResponseBody(responseBody)
        eventHandler.setCurrentRollbackRunId(rollbackRunId)

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

        val activeRun = activeRunMessageId?.let { runCardsByMessageId[it] }
        if (activeRun != null) {
            rollbackService.finishRun(activeRun.rollbackRunId)
            runCardsByMessageId.remove(activeRun.runMessageId)
            activeRunMessageId = null
        } else {
            rollbackService.finishSession(sessionId)
        }
        eventHandler.setCurrentRollbackRunId(null)
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

    private fun createAgentModelSelector(inputPanel: UserInputPanel): JComponent {
        val modelSettings = ModelSettings.getInstance()
        return AgentModelComboBoxAction(
            project,
            agentSession,
            {
                inputPanel.refreshModelDependentState()
            },
            { externalAgentId ->
                project.service<ExternalAcpAgentService>().closeSession(sessionId)
                agentSession.externalAgentId = externalAgentId
                agentSession.externalAgentSessionId = null
                agentSession.externalAgentMcpServerIds = emptySet()
                agentSession.externalAgentConfigOptions = emptyList()
                agentSession.externalAgentConfigSelections = emptyMap()
                agentSession.externalAgentErrorMessage = null
                agentSession.externalAgentConfigLoading = !externalAgentId.isNullOrBlank()
                project.messageBus.syncPublisher(AgentUiStateNotifier.AGENT_UI_STATE_TOPIC)
                    .sessionRuntimeChanged(sessionId)
                if (!externalAgentId.isNullOrBlank()) {
                    backgroundScope.launch {
                        runCatching {
                            project.service<ExternalAcpAgentService>().warmUpSession(agentSession)
                        }.onFailure { ex ->
                            agentSession.externalAgentConfigLoading = false
                            project.service<ExternalAcpAgentService>().closeSession(sessionId)
                            agentSession.externalAgentErrorMessage =
                                buildExternalAgentFailureMessage(externalAgentId, ex)
                            OverlayUtil.showNotification(
                                "${displayExternalAgentName(externalAgentId)} unavailable. ${agentSession.externalAgentErrorMessage}",
                                NotificationType.ERROR
                            )
                        }
                        withContext(Dispatchers.EDT) {
                            inputPanel.refreshModelDependentState()
                        }
                    }
                }
            },
            modelSettings.getServiceForFeature(FeatureType.AGENT),
            modelSettings.getAvailableProviders(FeatureType.AGENT),
            true
        ).createCustomComponent(com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN)
    }

    private fun displayExternalAgentName(externalAgentId: String): String {
        return ExternalAcpAgents.displayName(externalAgentId)
    }

    private fun buildExternalAgentFailureMessage(
        externalAgentId: String,
        throwable: Throwable
    ): String {
        return ExternalAcpAgents.buildFailureMessage(
            id = externalAgentId,
            throwable = throwable,
            fallbackMessage = "Failed to start ${displayExternalAgentName(externalAgentId)}"
        )
    }

    private fun buildExternalAgentConfigFailureMessage(throwable: Throwable): String {
        val message = throwable.message.orEmpty()
        return when {
            message.contains("does not support runtime option changes", ignoreCase = true) ->
                "This agent exposes runtime info but does not support changing it over ACP."

            message.isNotBlank() -> message
            else -> "Failed to update runtime option"
        }
    }

    private fun createAgentRuntimeOptionsSelector(inputPanel: UserInputPanel): JComponent {
        return AgentRuntimeOptionsComboBoxAction(agentSession) { optionId, value ->
            backgroundScope.launch {
                agentSession.externalAgentConfigLoading = true
                withContext(Dispatchers.EDT) {
                    inputPanel.refreshModelDependentState()
                }
                try {
                    runCatching {
                        project.service<ExternalAcpAgentService>()
                            .setSessionConfigOption(agentSession, optionId, value)
                    }.onFailure { ex ->
                        OverlayUtil.showNotification(
                            "${displayExternalAgentName(agentSession.externalAgentId ?: "agent")} option update failed. ${
                                buildExternalAgentConfigFailureMessage(ex)
                            }",
                            NotificationType.ERROR
                        )
                    }
                } finally {
                    agentSession.externalAgentConfigLoading = false
                }
                withContext(Dispatchers.EDT) {
                    inputPanel.refreshModelDependentState()
                }
            }
        }.createCustomComponent(com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN)
    }

    private fun displayLandingView() {
        clearLandingView()
        val landingPanel = createLandingView()
        activeLandingPanel = landingPanel
        scrollablePanel.displayLandingView(landingPanel)
    }

    private fun displayRecoveredConversation() {
        clearLandingView()
        scrollablePanel.clearAll()
        recoveredConversationJob?.cancel()
        recoveredConversationJob = backgroundScope.launch {
            val recoveredTurns =
                runCatching { loadRecoveredTurnsFromResumeCheckpoint() }.getOrNull()
            renderRecoveredConversation(recoveredTurns)
        }
    }

    private suspend fun renderRecoveredConversation(
        recoveredTurns: List<AgentCheckpointTurnSequencer.Turn>?
    ) {
        withContext(Dispatchers.EDT) {
            if (!isActive || project.isDisposed) return@withContext

            val canRenderInOrder = recoveredTurns != null &&
                    recoveredTurns.size == conversation.messages.size &&
                    recoveredTurns.indices.all { index ->
                        recoveredTurns[index].prompt == conversation.messages[index].prompt.orEmpty()
                            .trim()
                    }

            val messages = conversation.messages.toList()
            var nextIndex = 0
            while (nextIndex < messages.size) {
                if (!isActive || project.isDisposed) return@withContext

                val batchEnd = minOf(
                    nextIndex + RECOVERED_CONVERSATION_RENDER_BATCH_SIZE,
                    messages.size
                )

                while (nextIndex < batchEnd) {
                    if (!isActive || project.isDisposed) return@withContext

                    val index = nextIndex
                    val message = messages[index]
                    val prompt = message.prompt.orEmpty()
                    val wrapper = scrollablePanel.addMessage(message.id)
                    val userPanel = UserMessagePanel(project, message, this@AgentToolWindowTabPanel)
                    userPanel.addCopyAction { CopyAction.copyToClipboard(prompt) }
                    wrapper.add(userPanel)

                    val responseBody = ChatMessageResponseBody(
                        project,
                        false,
                        false,
                        false,
                        false,
                        false,
                        this@AgentToolWindowTabPanel
                    )

                    val renderedInOrder = if (canRenderInOrder) {
                        renderRecoveredTurnInOrder(responseBody, recoveredTurns[index].events)
                    } else {
                        false
                    }

                    if (!renderedInOrder) {
                        addRecoveredToolCards(responseBody, message)
                        responseBody.withResponse(message.response.orEmpty().stripThinkingBlocks())
                    }

                    val responsePanel = ResponseMessagePanel().apply {
                        setResponseContent(responseBody)
                    }
                    wrapper.add(responsePanel)
                    registerRecoveredRunCard(message, responsePanel)
                    nextIndex += 1
                }

                scrollablePanel.update()
                if (nextIndex < messages.size) {
                    yield()
                }
            }

            scrollablePanel.scrollToBottom()
        }
    }

    private suspend fun loadRecoveredTurnsFromResumeCheckpoint(): List<AgentCheckpointTurnSequencer.Turn>? {
        val resumeRef = agentSession.resumeCheckpointRef ?: return null
        val checkpoint = historyService.loadCheckpoint(resumeRef)
            ?: historyService.loadResumeCheckpoint(resumeRef)
            ?: return null
        val projectInstructions = loadProjectInstructions(project.basePath)
        return AgentCheckpointTurnSequencer.toVisibleTurns(
            history = checkpoint.messageHistory,
            projectInstructions = projectInstructions,
            preserveSyntheticContinuation = true
        )
    }

    private fun renderRecoveredTurnInOrder(
        responseBody: ChatMessageResponseBody,
        events: List<AgentCheckpointTurnSequencer.TurnEvent>
    ): Boolean {
        if (events.isEmpty()) {
            return false
        }

        val pendingById = mutableMapOf<String, ToolCallCard>()
        val pendingWithoutId = ArrayDeque<ToolCallCard>()
        var rendered = false

        events.forEach { event ->
            when (event) {
                is AgentCheckpointTurnSequencer.TurnEvent.Assistant -> {
                    val text = event.content.stripThinkingBlocks()
                    if (text.isNotBlank()) {
                        responseBody.withResponse(text)
                        rendered = true
                    }
                }

                is AgentCheckpointTurnSequencer.TurnEvent.Reasoning -> {
                    val text = event.content.stripThinkingBlocks()
                    if (text.isNotBlank()) {
                        responseBody.withResponse(text)
                        rendered = true
                    }
                }

                is AgentCheckpointTurnSequencer.TurnEvent.ToolCall -> {
                    val toolName = event.tool.ifBlank { "Tool" }
                    if (AgentCheckpointTurnSequencer.isTodoWriteTool(toolName)) {
                        return@forEach
                    }
                    val rawArgs = event.content
                    val args = parseRecoveredToolArgs(toolName, rawArgs)
                    val card = createRecoveredToolCard(toolName, args, rawArgs)
                    responseBody.addToolStatusPanel(card)
                    val callId = event.id?.takeIf { it.isNotBlank() }
                    if (callId != null) {
                        pendingById[callId] = card
                    } else {
                        pendingWithoutId.addLast(card)
                    }
                    rendered = true
                }

                is AgentCheckpointTurnSequencer.TurnEvent.ToolResult -> {
                    val toolName = event.tool.ifBlank { "Tool" }
                    if (AgentCheckpointTurnSequencer.isTodoWriteTool(toolName)) {
                        return@forEach
                    }
                    val rawResult = event.content
                    val parsedResult = parseRecoveredToolResult(toolName, rawResult)
                    val success = inferRecoveredToolSuccess(parsedResult, rawResult)
                    val card = event.id
                        ?.takeIf { it.isNotBlank() }
                        ?.let { pendingById.remove(it) }
                        ?: pendingWithoutId.pollFirst()
                        ?: run {
                            val orphan = createRecoveredToolCard(toolName, "", "")
                            responseBody.addToolStatusPanel(orphan)
                            orphan
                        }
                    card.complete(success, parsedResult ?: rawResult)
                    rendered = true
                }

            }
        }

        return rendered
    }

    private fun addRecoveredToolCards(responseBody: ChatMessageResponseBody, message: Message) {
        val toolCalls = message.toolCalls ?: return
        val toolCallResults = message.toolCallResults ?: emptyMap()

        toolCalls.forEach { toolCall ->
            val toolName = toolCall.function.name ?: return@forEach
            if (AgentCheckpointTurnSequencer.isTodoWriteTool(toolName)) {
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
            toolName = toolName,
            payload = payload
        ) ?: payload
    }

    private fun parseRecoveredToolResult(toolName: String, rawResult: String): Any? {
        val payload = rawResult.trim()
        if (payload.isBlank()) return null

        return ToolSpecs.decodeResultOrNull(
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

    private fun clearLandingView() {
        scrollablePanel.clearLandingViewIfVisible()
        activeLandingPanel?.let { Disposer.dispose(it) }
        activeLandingPanel = null
    }

    fun getSessionId(): String = sessionId

    fun getAgentSession(): AgentSession = agentSession

    fun getConversation(): Conversation = conversation

    fun getSelectedTags(): List<TagDetails> = userInputPanel.getSelectedTags()

    fun restoreDraftState(state: ToolWindowInitialState) {
        tagManager.clear()
        state.tags.forEach(userInputPanel::addTag)
    }

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

    private fun promoteQueuedMessageToActiveRun(message: MessageWithContext) {
        removeQueuedMessage(message.uiText)
        runCatching {
            project.service<AgentToolWindowContentManager>()
                .setTabStatus(sessionId, AgentToolWindowTabbedPane.TabStatus.RUNNING)
        }

        val rollbackRunId = rollbackService.startSession(sessionId)
        rollbackPanel.refreshOperations()

        val messagePanel = scrollablePanel.addMessage(message.id)
        val userPanel = UserMessagePanel(
            project,
            MessageBuilder(project, message.text).withTags(message.tags).build(),
            this
        )
        userPanel.addCopyAction { CopyAction.copyToClipboard(message.text) }
        messagePanel.add(userPanel)

        val responseBody = ChatMessageResponseBody(project, false, false, false, false, true, this)
        val responsePanel = ResponseMessagePanel()
        responsePanel.setResponseContent(responseBody)
        messagePanel.add(responsePanel)

        registerRunCard(
            runMessageId = message.id,
            rollbackRunId = rollbackRunId,
            responsePanel = responsePanel,
            prompt = message.text
        )

        eventHandler.resetForNewSubmission()
        eventHandler.setCurrentResponseBody(responseBody)
        eventHandler.setCurrentRollbackRunId(rollbackRunId)
        loadingLabel.text = CodeGPTBundle.get("toolwindow.chat.loading")
        loadingLabel.isVisible = true
        userInputPanel.setStopEnabled(true)

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

    private fun registerRunCard(
        runMessageId: UUID,
        rollbackRunId: String,
        responsePanel: ResponseMessagePanel,
        prompt: String
    ) {
        val state = RunCardState(
            runMessageId = runMessageId,
            rollbackRunId = rollbackRunId,
            responsePanel = responsePanel,
            sourceMessage = Message(prompt)
        )
        runCardsByMessageId[runMessageId] = state
        activeRunMessageId = runMessageId
    }

    private fun registerRecoveredRunCard(message: Message, responsePanel: ResponseMessagePanel) {
        val copiedMessage = Message(
            message.prompt.orEmpty(),
            message.response.orEmpty().stripThinkingBlocks()
        ).apply {
            message.toolCalls?.let { toolCalls = ArrayList(it) }
            message.toolCallResults?.let { toolCallResults = LinkedHashMap(it) }
        }
        val state = RunCardState(
            runMessageId = message.id,
            rollbackRunId = "",
            responsePanel = responsePanel,
            sourceMessage = copiedMessage,
            completed = true
        )
        runCardsByMessageId[message.id] = state
        timelineController.invalidateTimelineCache()
    }

    private fun markActiveRunCompleted() {
        val runMessageId = activeRunMessageId ?: return
        val state = runCardsByMessageId[runMessageId] ?: return
        state.completed = true
        activeRunMessageId = null
    }

    private fun updateRunCheckpoint(runMessageId: UUID, ref: CheckpointRef?) {
        val state = runCardsByMessageId[runMessageId] ?: return
        timelineController.invalidateTimelineCache()
        if (ref != null) {
            state.sourceMessage = null
        }
    }

    private fun showSessionStartTimelineDialog() {
        timelineController.showSessionStartTimelineDialog()
    }

    private fun runStateForRunIndex(runIndex: Int): AgentTimelineRunState? {
        if (runIndex <= 0) return null
        val state = runCardsByMessageId.values.elementAtOrNull(runIndex - 1) ?: return null
        return AgentTimelineRunState(
            rollbackRunId = state.rollbackRunId,
            sourceMessage = state.sourceMessage
        )
    }

    private fun applySeededSessionState(
        seededConversation: Conversation,
        seededMessageHistory: List<PromptMessage>
    ) {
        conversation.messages = seededConversation.messages
        runCardsByMessageId.clear()
        activeRunMessageId = null
        timelineController.invalidateTimelineCache()

        agentSession.agentId = null
        agentSession.resumeCheckpointRef = null
        agentSession.seededMessageHistory = seededMessageHistory

        val contentManager = project.service<AgentToolWindowContentManager>()
        contentManager.setAgentId(sessionId, null)
        contentManager.setResumeCheckpointRef(sessionId, null)
        contentManager.setSeededMessageHistory(sessionId, seededMessageHistory)

        project.service<AgentService>().clearPendingMessages(sessionId)
        loadingLabel.isVisible = false
        clearQueuedMessages()
        approvalContainer.removeAll()
        approvalContainer.isVisible = false
        eventHandler.resetForNewSubmission()
        eventHandler.setCurrentRollbackRunId(null)
        userInputPanel.setStopEnabled(false)

        if (conversation.messages.isEmpty()) {
            displayLandingView()
        } else {
            displayRecoveredConversation()
        }

        refreshViewAfterRollback()
        runCatching {
            contentManager.setTabStatus(sessionId, AgentToolWindowTabbedPane.TabStatus.STOPPED)
        }
    }

    private fun refreshViewAfterRollback() {
        timelineController.invalidateTimelineCache()
        runCatching { VirtualFileManager.getInstance().asyncRefresh(null) }
        rollbackPanel.refreshOperations()
        scrollablePanel.update()
        revalidate()
        repaint()
    }

    override fun dispose() {
        recoveredConversationJob?.cancel()
        clearLandingView()
        ToolRunContext.cleanupSession(sessionId)
        runCardsByMessageId.clear()
        activeRunMessageId = null

        projectMessageBusConnection.disconnect()
        appMessageBusConnection.disconnect()
    }
}
