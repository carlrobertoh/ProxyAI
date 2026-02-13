package ee.carlrobert.codegpt.toolwindow.agent

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.agent.AgentService
import ee.carlrobert.codegpt.agent.ProxyAIAgent.loadProjectInstructions
import ee.carlrobert.codegpt.agent.history.AgentCheckpointConversationMapper
import ee.carlrobert.codegpt.agent.history.AgentCheckpointHistoryService
import ee.carlrobert.codegpt.agent.history.AgentCheckpointTurnSequencer
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.agent.rollback.ChangeKind
import ee.carlrobert.codegpt.agent.rollback.FileChange
import ee.carlrobert.codegpt.agent.rollback.RollbackResult
import ee.carlrobert.codegpt.agent.rollback.RollbackService
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.toolwindow.chat.editor.actions.CopyAction
import ee.carlrobert.codegpt.util.StringUtil.stripThinkingBlocks
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.util.*
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import ai.koog.prompt.message.Message as PromptMessage

internal data class AgentTimelineRunState(
    val rollbackRunId: String,
    val sourceMessage: Message?
)

internal class AgentSessionTimelineController(
    private val project: Project,
    private val agentSession: AgentSession,
    private val conversation: Conversation,
    private val runStateForRunIndex: (Int) -> AgentTimelineRunState?,
    private val applySeededSessionState: (Conversation, CheckpointRef) -> Unit,
    private val onAfterRollbackRefresh: () -> Unit
) : Disposable {
    private val replayJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val rollbackService = RollbackService.getInstance(project)
    private val historyService = project.service<AgentCheckpointHistoryService>()
    private val historicalRollbackSupport =
        AgentSessionTimelineHistoricalRollbackSupport(project, historyService, replayJson)
    private val backgroundScope = DisposableCoroutineScope(Dispatchers.IO)

    private var sessionTimelinePointsCache: List<RunTimelinePoint>? = null

    private fun launchBackground(block: suspend () -> Unit) {
        backgroundScope.launch {
            block()
        }
    }

    override fun dispose() {
        backgroundScope.dispose()
    }

    fun invalidateTimelineCache() {
        sessionTimelinePointsCache = null
    }

    fun showSessionStartTimelineDialog() {
        val agentService = project.service<AgentService>()
        val sessionId = agentSession.sessionId
        if (agentService.isSessionRunning(sessionId)) {
            Messages.showErrorDialog(
                project,
                "Stop the active run before opening timeline context editor.",
                "Agent Timeline"
            )
            return
        }

        launchBackground {
            val payload = runCatching {
                val points = loadSessionTimelinePoints()
                val timelineBaseHistory = loadSessionTimelineBaseHistory()
                val snapshot = loadSessionContextSnapshot(timelineBaseHistory)
                points to snapshot
            }.getOrNull()
            val points = payload?.first.orEmpty()
            if (points.isEmpty()) {
                runInEdt {
                    Messages.showErrorDialog(
                        project,
                        "No timeline points are available for this session yet.",
                        "Start New Session"
                    )
                }
                return@launchBackground
            }

            runInEdt {
                val snapshot = payload?.second
                showTimelineDialog(
                    points = points,
                    onSelect = { point ->
                        startNewSessionFromTimelinePoint(point)
                    },
                    reloadPoints = { loadSessionTimelinePoints(forceRefresh = true) },
                    contextSnapshot = snapshot
                )
            }
        }
    }

    private fun loadSessionContextSnapshot(
        timelineBaseHistory: List<PromptMessage>? = null
    ): SessionContextSnapshot? {
        if (timelineBaseHistory != null) {
            val baseHistory = timelineBaseHistory.toList()
            if (baseHistory.none { it !is PromptMessage.System }) {
                return null
            }
            return SessionContextSnapshot(baseHistory = baseHistory)
        }

        val fallbackHistory = buildPromptHistoryFromConversation(conversation.messages)
        if (fallbackHistory.none { it !is PromptMessage.System }) {
            return null
        }
        return SessionContextSnapshot(baseHistory = fallbackHistory)
    }

    private suspend fun loadSessionTimelineBaseHistory(): List<PromptMessage>? {
        val agentId = resolveTimelineAgentId() ?: return null
        val checkpoints = loadTimelineCheckpoints(agentId)
        return checkpoints.lastOrNull()?.messageHistory?.toList()
    }

    private fun resolveTimelineAgentId(): String? {
        val resumeRef = agentSession.resumeCheckpointRef
        return resumeRef?.agentId ?: agentSession.runtimeAgentId
    }

    private suspend fun loadTimelineCheckpoints(agentId: String): List<AgentCheckpointData> {
        val resumeRef = agentSession.resumeCheckpointRef
        var checkpoints = historyService.listCheckpoints(agentId).sortedBy { it.createdAt }
        if (resumeRef != null) {
            val anchorIndex = checkpoints.indexOfFirst { it.checkpointId == resumeRef.checkpointId }
            if (anchorIndex >= 0) {
                checkpoints = checkpoints.subList(0, anchorIndex + 1)
            }
        }
        return checkpoints
    }

    private fun buildPromptHistoryFromConversation(messages: List<Message>): List<PromptMessage> {
        return buildList {
            messages.forEach { message ->
                val prompt = message.prompt.orEmpty().trim()
                if (prompt.isBlank()) return@forEach
                add(PromptMessage.User(prompt, RequestMetaInfo.Empty))

                val response = message.response.orEmpty().stripThinkingBlocks().trim()
                if (response.isNotBlank()) {
                    add(PromptMessage.Assistant(response, ResponseMetaInfo.Empty))
                }
            }
        }
    }

    private fun applyEditedSessionContext(
        baseHistory: List<PromptMessage>,
        selectedNonSystemMessageCounts: Set<Int>,
        alwaysIncludedNonSystemMessageCounts: Set<Int>
    ) {
        launchBackground {
            val payload = runCatching {
                buildSeededContextPayload(
                    baseHistory = baseHistory,
                    selectedNonSystemMessageCounts = selectedNonSystemMessageCounts,
                    alwaysIncludedNonSystemMessageCounts = alwaysIncludedNonSystemMessageCounts
                )
            }.getOrNull()

            runInEdt {
                if (payload == null) {
                    Messages.showErrorDialog(
                        project,
                        "Unable to apply session context changes.",
                        "Edit Session Context"
                    )
                    return@runInEdt
                }

                val (seededConversation, seedRef) = payload
                invalidateTimelineCache()
                applySeededSessionState(seededConversation, seedRef)
            }
        }
    }

    private fun startNewSessionFromEditedContext(
        baseHistory: List<PromptMessage>,
        selectedNonSystemMessageCounts: Set<Int>,
        alwaysIncludedNonSystemMessageCounts: Set<Int>
    ) {
        launchBackground {
            val payload = runCatching {
                buildSeededContextPayload(
                    baseHistory = baseHistory,
                    selectedNonSystemMessageCounts = selectedNonSystemMessageCounts,
                    alwaysIncludedNonSystemMessageCounts = alwaysIncludedNonSystemMessageCounts
                )
            }.getOrNull()

            runInEdt {
                if (payload == null) {
                    Messages.showErrorDialog(
                        project,
                        "Unable to create a new session from selected checkpoints.",
                        "Agent Timeline"
                    )
                    return@runInEdt
                }

                val (seededConversation, seedRef) = payload
                val newSession = AgentSession(
                    sessionId = UUID.randomUUID().toString(),
                    conversation = seededConversation,
                    runtimeAgentId = seedRef.agentId,
                    resumeCheckpointRef = seedRef
                )
                project.service<AgentToolWindowContentManager>()
                    .createNewAgentTab(newSession, select = true)
            }
        }
    }

    private suspend fun buildSeededContextPayload(
        baseHistory: List<PromptMessage>,
        selectedNonSystemMessageCounts: Set<Int>,
        alwaysIncludedNonSystemMessageCounts: Set<Int>
    ): Pair<Conversation, CheckpointRef>? {
        val history = rebuildHistoryFromEditedContext(
            baseHistory = baseHistory,
            selectedNonSystemMessageCounts = selectedNonSystemMessageCounts,
            alwaysIncludedNonSystemMessageCounts = alwaysIncludedNonSystemMessageCounts
        )
        return createSeededConversationFromHistory(history)
    }

    private suspend fun createSeededConversationFromHistory(history: List<PromptMessage>): Pair<Conversation, CheckpointRef>? {
        if (history.none { it !is PromptMessage.System }) return null

        val seedRef = project.service<AgentService>()
            .createSeedCheckpointFromHistory(history)
            ?: return null
        val seedCheckpoint = historyService.loadCheckpoint(seedRef) ?: return null
        val seededConversation = AgentCheckpointConversationMapper.toConversation(
            checkpoint = seedCheckpoint,
            projectInstructions = loadProjectInstructions(project.basePath)
        )
        return seededConversation to seedRef
    }

    private fun rebuildHistoryFromEditedContext(
        baseHistory: List<PromptMessage>,
        selectedNonSystemMessageCounts: Set<Int>,
        alwaysIncludedNonSystemMessageCounts: Set<Int>
    ): List<PromptMessage> {
        val includeIndexes = (selectedNonSystemMessageCounts + alwaysIncludedNonSystemMessageCounts)
            .filter { it > 0 }
            .toMutableSet()
        if (includeIndexes.isEmpty()) {
            return baseHistory.filterIsInstance<PromptMessage.System>()
        }

        val nonSystemMessages = baseHistory.filterNot { it is PromptMessage.System }
        val selectedToolCallIds = mutableSetOf<String>()

        includeIndexes.toList().forEach { oneBasedIndex ->
            val zeroBasedIndex = oneBasedIndex - 1
            if (zeroBasedIndex !in nonSystemMessages.indices) return@forEach
            if (nonSystemMessages[zeroBasedIndex] is PromptMessage.User) return@forEach

            var cursor = zeroBasedIndex - 1
            while (cursor >= 0) {
                if (nonSystemMessages[cursor] is PromptMessage.User) {
                    includeIndexes += cursor + 1
                    break
                }
                cursor -= 1
            }
        }

        nonSystemMessages.forEachIndexed { index, message ->
            val nonSystemIndex = index + 1
            if (nonSystemIndex !in includeIndexes) return@forEachIndexed

            val toolCall = message as? PromptMessage.Tool.Call ?: return@forEachIndexed
            val callId = toolCall.id?.takeIf { it.isNotBlank() }
            if (callId != null) selectedToolCallIds += callId

            var cursor = index + 1
            while (cursor < nonSystemMessages.size) {
                val nextResult = nonSystemMessages[cursor] as? PromptMessage.Tool.Result ?: break
                val nextResultId = nextResult.id?.takeIf { it.isNotBlank() }
                if (callId != null && nextResultId != null && nextResultId != callId) break
                includeIndexes += cursor + 1
                cursor += 1
            }
        }

        if (selectedToolCallIds.isNotEmpty()) {
            nonSystemMessages.forEachIndexed { index, message ->
                val result = message as? PromptMessage.Tool.Result ?: return@forEachIndexed
                val resultId = result.id?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                if (resultId in selectedToolCallIds) includeIndexes += index + 1
            }
        }

        val rebuilt = mutableListOf<PromptMessage>()
        var nonSystemIndex = 0
        baseHistory.forEach { message ->
            if (message is PromptMessage.System) {
                rebuilt += message
                return@forEach
            }
            nonSystemIndex += 1
            if (nonSystemIndex in includeIndexes) rebuilt += message
        }
        return rebuilt
    }

    private fun computeAlwaysIncludedNonSystemMessageCounts(
        baseHistory: List<PromptMessage>,
        points: List<RunTimelinePoint>
    ): Set<Int> {
        val selectableNonSystemMessageCounts = points
            .mapNotNull { it.nonSystemMessageCount }
            .filter { it > 0 }
            .toSet()
        val nonSystemMessages = baseHistory.filterNot { it is PromptMessage.System }

        return nonSystemMessages.mapIndexedNotNull { index, message ->
            val nonSystemMessageCount = index + 1
            if (nonSystemMessageCount in selectableNonSystemMessageCounts) return@mapIndexedNotNull null
            if (message is PromptMessage.Tool.Result && !isInternalTimelineTool(message.tool)) {
                return@mapIndexedNotNull null
            }
            nonSystemMessageCount
        }.toSet()
    }

    private fun estimatePromptHistoryTokenCount(history: List<PromptMessage>): Int {
        val encodingManager = EncodingManager.getInstance()
        return history.sumOf { message ->
            val text = when (message) {
                is PromptMessage.System -> message.content
                is PromptMessage.User -> message.content
                is PromptMessage.Assistant -> message.content
                is PromptMessage.Reasoning -> message.content
                is PromptMessage.Tool.Call -> message.content
                is PromptMessage.Tool.Result -> message.content
            }
            if (text.isBlank()) 0 else encodingManager.countTokens(text)
        }
    }

    private suspend fun loadSessionTimelinePoints(forceRefresh: Boolean = false): List<RunTimelinePoint> {
        if (!forceRefresh) {
            sessionTimelinePointsCache?.let { return it }
        }

        val points = mutableListOf<RunTimelinePoint>()
        val agentId = resolveTimelineAgentId()

        if (!agentId.isNullOrBlank()) {
            val checkpoints = loadTimelineCheckpoints(agentId)
            points += buildSessionTimelinePointsFromCheckpoints(agentId, checkpoints)
        }

        sessionTimelinePointsCache = points
        return points
    }

    private fun buildSessionTimelinePointsFromCheckpoints(
        agentId: String,
        checkpoints: List<AgentCheckpointData>
    ): List<RunTimelinePoint> {
        val checkpoint = checkpoints.lastOrNull() ?: return emptyList()
        val projectInstructions = loadProjectInstructions(project.basePath)
        val checkpointRef = CheckpointRef(agentId, checkpoint.checkpointId)
        val turns = AgentCheckpointTurnSequencer.toVisibleTurns(
            history = checkpoint.messageHistory,
            projectInstructions = projectInstructions,
            preserveSyntheticContinuation = true
        )
        if (turns.isEmpty()) return emptyList()

        val points = mutableListOf<RunTimelinePoint>()
        val nonSystemMessages = checkpoint.messageHistory.filterNot { it is PromptMessage.System }
        turns.forEachIndexed { index, turn ->
            val runIndex = index + 1
            val runLabel =
                resolveRunLabelForTurn(nonSystemMessages = nonSystemMessages, turn = turn)

            points.add(
                RunTimelinePoint(
                    cacheKey = "${checkpoint.checkpointId}:${turn.userNonSystemMessageCount}",
                    checkpointRef = checkpointRef,
                    title = "User message",
                    subtitle = AgentMessageText.abbreviate(turn.prompt, 120),
                    runLabel = runLabel,
                    icon = AllIcons.General.User,
                    nonSystemMessageCount = turn.userNonSystemMessageCount,
                    kind = TimelinePointKind.USER,
                    runIndex = runIndex
                )
            )

            turn.events.forEach { event ->
                toTimelinePointFromTurnEvent(
                    checkpoint = checkpoint,
                    checkpointRef = checkpointRef,
                    event = event,
                    runIndex = runIndex,
                    runLabel = runLabel
                )?.let(points::add)
            }
        }
        return points
    }

    private fun resolveRunLabelForTurn(
        nonSystemMessages: List<PromptMessage>,
        turn: AgentCheckpointTurnSequencer.Turn
    ): String {
        val userMessageText = AgentMessageText.abbreviate(turn.prompt, 80)
        val userIndex = turn.userNonSystemMessageCount - 1
        if (userIndex !in nonSystemMessages.indices) return userMessageText

        var cursor = userIndex + 1
        while (cursor < nonSystemMessages.size) {
            val message = nonSystemMessages[cursor]
            if (message is PromptMessage.User) break

            val call = message as? PromptMessage.Tool.Call
            if (call != null && isTodoWriteTool(call.tool)) {
                extractTodoWriteRunLabel(call.content)?.let { return it }
            }
            cursor += 1
        }

        return userMessageText
    }

    private fun toTimelinePointFromTurnEvent(
        checkpoint: AgentCheckpointData,
        checkpointRef: CheckpointRef,
        event: AgentCheckpointTurnSequencer.TurnEvent,
        runIndex: Int,
        runLabel: String
    ): RunTimelinePoint? {
        return when (event) {
            is AgentCheckpointTurnSequencer.TurnEvent.Assistant -> {
                val text = event.content.stripThinkingBlocks().trim()
                if (text.isBlank()) return null
                RunTimelinePoint(
                    cacheKey = "${checkpoint.checkpointId}:${event.nonSystemMessageCount}",
                    checkpointRef = checkpointRef,
                    title = "Assistant response",
                    subtitle = AgentMessageText.abbreviate(text, 120),
                    runLabel = runLabel,
                    icon = AllIcons.General.Balloon,
                    nonSystemMessageCount = event.nonSystemMessageCount,
                    outputText = text,
                    kind = TimelinePointKind.ASSISTANT,
                    runIndex = runIndex
                )
            }

            is AgentCheckpointTurnSequencer.TurnEvent.Reasoning -> {
                val text = event.content.stripThinkingBlocks().trim()
                if (text.isBlank()) return null
                RunTimelinePoint(
                    cacheKey = "${checkpoint.checkpointId}:${event.nonSystemMessageCount}",
                    checkpointRef = checkpointRef,
                    title = "Assistant reasoning",
                    subtitle = AgentMessageText.abbreviate(text, 120),
                    runLabel = runLabel,
                    icon = AllIcons.General.ContextHelp,
                    nonSystemMessageCount = event.nonSystemMessageCount,
                    outputText = text,
                    kind = TimelinePointKind.REASONING,
                    runIndex = runIndex
                )
            }

            is AgentCheckpointTurnSequencer.TurnEvent.ToolCall -> {
                val toolName = event.tool.ifBlank { "Tool" }
                if (isInternalTimelineTool(toolName)) return null
                RunTimelinePoint(
                    cacheKey = "${checkpoint.checkpointId}:${event.nonSystemMessageCount}",
                    checkpointRef = checkpointRef,
                    title = toolName,
                    subtitle = extractToolCallSubtitle(toolName, event.content),
                    runLabel = runLabel,
                    icon = iconForTool(toolName),
                    nonSystemMessageCount = event.nonSystemMessageCount,
                    toolCallId = event.id,
                    kind = TimelinePointKind.TOOL_CALL,
                    runIndex = runIndex
                )
            }

            is AgentCheckpointTurnSequencer.TurnEvent.ToolResult -> null
        }
    }

    private fun showTimelineDialog(
        points: List<RunTimelinePoint>,
        onSelect: (RunTimelinePoint) -> Unit,
        reloadPoints: (suspend () -> List<RunTimelinePoint>)?,
        contextSnapshot: SessionContextSnapshot?
    ) {
        var dialog: DialogWrapper? = null
        var onSelectionStateChanged: (() -> Unit)? = null
        val ui = AgentSessionTimelineDialogBuilder.build(
            points = points,
            onSelect = onSelect,
            reloadPoints = reloadPoints,
            contextSelectionEnabled = contextSnapshot != null,
            onNonCopyMenuAction = {
                dialog?.close(DialogWrapper.CANCEL_EXIT_CODE)
            },
            onSelectionStateChanged = {
                onSelectionStateChanged?.invoke()
            },
            timelinePointDisplayLabel = ::timelinePointDisplayLabel,
            canRollbackTimelinePoint = ::canRollbackTimelinePoint,
            rollbackToTimelinePoint = ::rollbackToTimelinePoint,
            canCopyTimelinePointOutput = ::canCopyTimelinePointOutput,
            copyTimelinePointOutput = ::copyTimelinePointOutput,
            launchBackground = ::launchBackground
        )

        if (contextSnapshot != null) {
            val alwaysIncludedNonSystemMessageCounts = computeAlwaysIncludedNonSystemMessageCounts(
                baseHistory = contextSnapshot.baseHistory,
                points = points
            )

            fun checkedMessageCountsOrError(): Set<Int>? {
                val checkedMessageCounts = ui.getCheckedNonSystemMessageCounts()
                if (checkedMessageCounts.isEmpty()) {
                    Messages.showErrorDialog(
                        project,
                        "Check at least one run or checkpoint.",
                        "Agent Timeline"
                    )
                    return null
                }
                return checkedMessageCounts
            }

            val contextStatsLabel = JBLabel().apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                font = JBUI.Fonts.smallFont()
                isVisible = true
            }

            fun updateContextStats() {
                val history = if (ui.isEditMode()) {
                    val selectedCounts = ui.getCheckedNonSystemMessageCounts()
                    rebuildHistoryFromEditedContext(
                        baseHistory = contextSnapshot.baseHistory,
                        selectedNonSystemMessageCounts = selectedCounts,
                        alwaysIncludedNonSystemMessageCounts = alwaysIncludedNonSystemMessageCounts
                    )
                } else {
                    contextSnapshot.baseHistory
                }
                val messageCount = history.count { it !is PromptMessage.System }
                val tokenCount = estimatePromptHistoryTokenCount(history)
                val messageSuffix = if (messageCount == 1) "message" else "messages"
                val tokenText = "%,d".format(tokenCount)
                contextStatsLabel.text = if (ui.isEditMode()) {
                    "Selected: $messageCount $messageSuffix • ~$tokenText tokens"
                } else {
                    "Context: $messageCount $messageSuffix • ~$tokenText tokens"
                }
            }

            onSelectionStateChanged = { updateContextStats() }

            dialog = object : DialogWrapper(project, true) {
                private val closeAction = object : DialogWrapperAction("Close") {
                    override fun doAction(e: ActionEvent?) {
                        close(CANCEL_EXIT_CODE)
                    }
                }
                private val editAction = object : DialogWrapperAction("Edit") {
                    override fun doAction(e: ActionEvent?) {
                        ui.setEditMode(true)
                        syncActions()
                    }
                }
                private val cancelAction = object : DialogWrapperAction("Cancel") {
                    override fun doAction(e: ActionEvent?) {
                        ui.setEditMode(false)
                        syncActions()
                    }
                }
                private val editSessionAction = object : DialogWrapperAction("Apply") {
                    override fun doAction(e: ActionEvent?) {
                        val selectedMessageCounts = checkedMessageCountsOrError() ?: return
                        close(OK_EXIT_CODE)
                        applyEditedSessionContext(
                            baseHistory = contextSnapshot.baseHistory,
                            selectedNonSystemMessageCounts = selectedMessageCounts,
                            alwaysIncludedNonSystemMessageCounts = alwaysIncludedNonSystemMessageCounts
                        )
                    }
                }
                private val newSessionAction = object : DialogWrapperAction("New Session") {
                    override fun doAction(e: ActionEvent?) {
                        val selectedMessageCounts = checkedMessageCountsOrError() ?: return
                        close(OK_EXIT_CODE)
                        startNewSessionFromEditedContext(
                            baseHistory = contextSnapshot.baseHistory,
                            selectedNonSystemMessageCounts = selectedMessageCounts,
                            alwaysIncludedNonSystemMessageCounts = alwaysIncludedNonSystemMessageCounts
                        )
                    }
                }
                private var closeButton: JButton? = null
                private var editButton: JButton? = null
                private var newSessionButton: JButton? = null
                private lateinit var actionCards: JPanel
                private lateinit var actionCardLayout: CardLayout

                private fun syncActions() {
                    val inEdit = ui.isEditMode()
                    if (::actionCards.isInitialized) {
                        actionCardLayout.show(actionCards, if (inEdit) "edit" else "view")
                    }
                    rootPane?.defaultButton =
                        if (inEdit) newSessionButton else editButton ?: closeButton
                    actionCards.revalidate()
                    actionCards.repaint()
                    updateContextStats()
                }

                init {
                    title = "Agent Timeline"
                    isResizable = true
                    init()
                    syncActions()
                }

                override fun createCenterPanel(): JComponent {
                    return JPanel(BorderLayout()).apply {
                        border = JBUI.Borders.empty(8, 10, 0, 10)
                        add(ui.component, BorderLayout.CENTER)
                    }
                }

                override fun createSouthPanel(): JComponent {
                    val closeBtn = createJButtonForAction(closeAction).also { closeButton = it }
                    val editBtn = createJButtonForAction(editAction).also { editButton = it }
                    val cancelBtn = createJButtonForAction(cancelAction)
                    val editSessionBtn = createJButtonForAction(editSessionAction)
                    val newSessionBtn = createJButtonForAction(newSessionAction).also {
                        newSessionButton = it
                    }.apply {
                        putClientProperty("JButton.buttonType", "default")
                    }

                    val viewButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                        isOpaque = false
                        add(closeBtn)
                        add(editBtn)
                    }
                    val editButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                        isOpaque = false
                        add(cancelBtn)
                        add(editSessionBtn)
                        add(newSessionBtn)
                    }

                    actionCardLayout = CardLayout()
                    actionCards = JPanel(actionCardLayout).apply {
                        isOpaque = false
                        add(viewButtons, "view")
                        add(editButtons, "edit")
                    }
                    syncActions()

                    return JPanel(BorderLayout()).apply {
                        isOpaque = false
                        border = JBUI.Borders.empty(8, 10, 8, 10)
                        add(contextStatsLabel, BorderLayout.WEST)
                        add(actionCards, BorderLayout.EAST)
                    }
                }

                override fun createActions(): Array<Action> = emptyArray()
            }
            dialog.show()
            return
        }

        dialog = object : DialogWrapper(project, true) {
            init {
                title = "Agent Timeline"
                isResizable = true
                init()
            }

            override fun createCenterPanel(): JComponent {
                return JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(8, 10, 6, 10)
                    add(ui.component, BorderLayout.CENTER)
                }
            }

            override fun createActions(): Array<Action> = emptyArray()
        }
        dialog.show()
    }

    private fun startNewSessionFromTimelinePoint(point: RunTimelinePoint) {
        launchBackground {
            val payload = runCatching {
                val checkpointRef = point.checkpointRef ?: return@runCatching null
                val checkpoint =
                    historyService.loadCheckpoint(checkpointRef) ?: return@runCatching null
                val messageHistory = trimHistoryByNonSystemCount(
                    checkpoint.messageHistory,
                    point.nonSystemMessageCount
                )
                if (messageHistory.none { it !is PromptMessage.System }) return@runCatching null

                createSeededConversationFromHistory(messageHistory)
            }.getOrNull()

            if (payload == null) {
                runInEdt {
                    Messages.showErrorDialog(
                        project,
                        "Unable to start a new session from the selected timeline point.",
                        "Start New Session"
                    )
                }
                return@launchBackground
            }

            runInEdt {
                val (seededConversation, seedRef) = payload
                val newSession = AgentSession(
                    sessionId = UUID.randomUUID().toString(),
                    conversation = seededConversation,
                    runtimeAgentId = seedRef.agentId,
                    resumeCheckpointRef = seedRef
                )
                project.service<AgentToolWindowContentManager>()
                    .createNewAgentTab(newSession, select = true)
            }
        }
    }

    private fun trimHistoryByNonSystemCount(
        history: List<PromptMessage>,
        nonSystemMessageCount: Int?
    ): List<PromptMessage> {
        val limit = nonSystemMessageCount ?: return history
        if (limit <= 0) return history.filterIsInstance<PromptMessage.System>()

        val trimmed = mutableListOf<PromptMessage>()
        var remaining = limit
        history.forEach { message ->
            if (message is PromptMessage.System) {
                trimmed.add(message)
                return@forEach
            }
            if (remaining <= 0) return@forEach
            trimmed.add(message)
            remaining -= 1
        }
        return trimmed
    }

    private fun isInternalTimelineTool(toolName: String): Boolean {
        return AgentCheckpointTurnSequencer.isTodoWriteTool(toolName)
    }

    private fun isTodoWriteTool(toolName: String): Boolean {
        return toolName.equals("TodoWrite", ignoreCase = true) ||
                toolName.equals("TodoWriteTool", ignoreCase = true)
    }

    private fun extractTodoWriteRunLabel(rawArgs: String): String? {
        val argsObject =
            runCatching { replayJson.parseToJsonElement(rawArgs).jsonObject }.getOrNull()
                ?: return null

        listOf("title", "short_description", "description", "summary", "task").forEach { key ->
            val value = stringValue(argsObject[key])?.trim().orEmpty()
            if (value.isNotBlank()) return AgentMessageText.abbreviate(value, 80)
        }

        val todos = argsObject["todos"]?.let { element ->
            runCatching { element.jsonArray }.getOrNull()
        }.orEmpty()
        todos.forEach { todo ->
            val todoObject = runCatching { todo.jsonObject }.getOrNull() ?: return@forEach
            listOf("content", "title", "task", "description").forEach { key ->
                val value = stringValue(todoObject[key])?.trim().orEmpty()
                if (value.isNotBlank()) return AgentMessageText.abbreviate(value, 80)
            }
        }

        return null
    }

    private fun iconForTool(toolName: String): javax.swing.Icon {
        val normalized = toolName.lowercase()
        return when {
            normalized.contains("read") -> AllIcons.Actions.Show
            normalized.contains("bash") || normalized.contains("shell") -> AllIcons.Nodes.Console
            normalized.contains("search") -> AllIcons.Actions.Search
            normalized.contains("web") -> AllIcons.General.Web
            normalized.contains("write") || normalized.contains("edit") -> AllIcons.Actions.Edit
            else -> AllIcons.Nodes.Function
        }
    }

    private fun extractToolCallSubtitle(toolName: String, rawArgs: String): String {
        val argsObject =
            runCatching { replayJson.parseToJsonElement(rawArgs).jsonObject }.getOrNull()
        if (argsObject == null) return AgentMessageText.abbreviate(rawArgs, 140)

        val preferredKeys = when {
            toolName.equals("Read", ignoreCase = true) -> listOf(
                "file_path",
                "path",
                "pathInProject"
            )

            toolName.contains("Bash", ignoreCase = true) -> listOf("command", "cmd")
            toolName.contains("Search", ignoreCase = true) -> listOf(
                "query",
                "searchText",
                "regexPattern",
                "pattern",
                "q"
            )

            isTodoWriteTool(toolName) -> listOf(
                "title",
                "short_description",
                "description",
                "summary",
                "task"
            )

            else -> listOf(
                "short_description",
                "description",
                "file_path",
                "path",
                "query",
                "command"
            )
        }

        preferredKeys.forEach { key ->
            val value = stringValue(argsObject[key]) ?: return@forEach
            if (value.isNotBlank()) return AgentMessageText.abbreviate(value, 140)
        }

        val firstEntryValue = argsObject.entries.asSequence()
            .mapNotNull { (_, value) -> stringValue(value) }
            .firstOrNull { it.isNotBlank() }
            ?.let { AgentMessageText.abbreviate(it, 140) }
        if (!firstEntryValue.isNullOrBlank()) return firstEntryValue

        return AgentMessageText.abbreviate(rawArgs, 140)
    }

    private fun stringValue(element: JsonElement?): String? {
        if (element == null) return null
        val primitive = element as? JsonPrimitive
        return if (primitive != null && primitive.isString) primitive.content else element.toString()
    }

    private fun timelinePointDisplayLabel(point: RunTimelinePoint): String {
        val runNumber = if (point.runIndex <= 0) 1 else point.runIndex
        val base = point.title.ifBlank { "Timeline point" }
        val subtitle = point.subtitle.trim()
        return if (subtitle.isBlank()) {
            "Run $runNumber • $base"
        } else {
            "Run $runNumber • $base: ${AgentMessageText.abbreviate(subtitle, 80)}"
        }
    }

    private fun canRollbackTimelinePoint(point: RunTimelinePoint): Boolean {
        val runState = runStateForTimelinePoint(point)
        if (runState != null && runState.rollbackRunId.isNotBlank()) {
            if (rollbackService.getRunSnapshot(runState.rollbackRunId)?.changes?.isNotEmpty() == true) {
                return true
            }
        }
        return point.checkpointRef != null && point.nonSystemMessageCount != null
    }

    private fun runStateForTimelinePoint(point: RunTimelinePoint): AgentTimelineRunState? {
        val runNumber = if (point.runIndex <= 0) 1 else point.runIndex
        return runStateForRunIndex(runNumber)
    }

    private fun rollbackToTimelinePoint(
        point: RunTimelinePoint,
        selectedLabel: String,
        onCompleted: (Boolean) -> Unit = {}
    ) {
        val runState = runStateForTimelinePoint(point)
        val rollbackRunId = runState?.rollbackRunId?.takeIf { it.isNotBlank() }
        if (rollbackRunId != null) {
            val snapshot = rollbackService.getRunSnapshot(rollbackRunId)
            val displayableChanges = snapshot?.changes
                ?.filter { rollbackService.isDisplayable(it.path) }
                .orEmpty()
            if (displayableChanges.isNotEmpty()) {
                val confirm = Messages.showYesNoDialog(
                    project,
                    buildRollbackConfirmationText(selectedLabel, displayableChanges),
                    "Rollback",
                    "Rollback",
                    "Cancel",
                    AllIcons.General.WarningDialog
                )
                if (confirm != Messages.YES) return

                launchBackground {
                    val result = rollbackService.rollbackRun(rollbackRunId)
                    runInEdt {
                        when (result) {
                            is RollbackResult.Success -> {
                                if (point.checkpointRef != null && point.nonSystemMessageCount != null) {
                                    syncCurrentSessionViewToTimelinePoint(point, onCompleted)
                                } else {
                                    onAfterRollbackRefresh()
                                    onCompleted(true)
                                }
                            }

                            is RollbackResult.Failure -> {
                                onCompleted(false)
                                Messages.showErrorDialog(project, result.message, "Rollback Failed")
                            }
                        }
                    }
                }
                return
            }
        }

        val checkpointRef = point.checkpointRef
        if (checkpointRef == null || point.nonSystemMessageCount == null) {
            Messages.showErrorDialog(
                project,
                "Rollback is not available for the selected entry.",
                "Rollback"
            )
            onCompleted(false)
            return
        }

        launchBackground {
            val operations = historicalRollbackSupport.collectOperations(point)
            runInEdt {
                if (operations.isEmpty()) {
                    val confirm = Messages.showYesNoDialog(
                        project,
                        """
                            This will rewind the current session to:
                            $selectedLabel

                            Continue?
                        """.trimIndent(),
                        "Rollback",
                        "Rollback",
                        "Cancel",
                        AllIcons.General.WarningDialog
                    )
                    if (confirm != Messages.YES) {
                        onCompleted(false)
                        return@runInEdt
                    }
                    syncCurrentSessionViewToTimelinePoint(point, onCompleted)
                    return@runInEdt
                }

                val confirm = Messages.showYesNoDialog(
                    project,
                    historicalRollbackSupport.buildConfirmationText(selectedLabel, operations),
                    "Rollback",
                    "Rollback",
                    "Cancel",
                    AllIcons.General.WarningDialog
                )
                if (confirm != Messages.YES) {
                    onCompleted(false)
                    return@runInEdt
                }

                launchBackground {
                    val errors = historicalRollbackSupport.applyOperations(operations)
                    runInEdt {
                        syncCurrentSessionViewToTimelinePoint(point, onCompleted)
                        if (errors.isNotEmpty()) {
                            val details = errors.take(10).joinToString(separator = "\n")
                            val suffix =
                                if (errors.size > 10) "\n...and ${errors.size - 10} more error(s)." else ""
                            Messages.showErrorDialog(project, "$details$suffix", "Rollback Failed")
                        }
                    }
                }
            }
        }
    }

    private fun syncCurrentSessionViewToTimelinePoint(
        point: RunTimelinePoint,
        onCompleted: (Boolean) -> Unit = {}
    ) {
        launchBackground {
            val payload =
                runCatching { buildSessionStateFromTimelinePoint(point) }.getOrNull()

            runInEdt {
                if (payload == null) {
                    onAfterRollbackRefresh()
                    onCompleted(false)
                    return@runInEdt
                }

                val (seededConversation, seedRef) = payload
                invalidateTimelineCache()
                applySeededSessionState(seededConversation, seedRef)
                onCompleted(true)
            }
        }
    }

    private suspend fun buildSessionStateFromTimelinePoint(
        point: RunTimelinePoint
    ): Pair<Conversation, CheckpointRef>? {
        val checkpointRef = point.checkpointRef ?: return null
        val checkpoint = historyService.listCheckpoints(checkpointRef.agentId).firstOrNull()
            ?: historyService.loadCheckpoint(checkpointRef)
            ?: return null
        val messageHistory =
            trimHistoryByNonSystemCount(checkpoint.messageHistory, point.nonSystemMessageCount)
        if (messageHistory.none { it !is PromptMessage.System }) return null

        return createSeededConversationFromHistory(messageHistory)
    }

    private fun buildRollbackConfirmationText(
        selectedLabel: String,
        changes: List<FileChange>
    ): String {
        val previewLimit = 12
        val listedChanges = changes.take(previewLimit).joinToString(separator = "\n") { change ->
            val symbol = when (change.kind) {
                ChangeKind.ADDED -> "+"
                ChangeKind.DELETED -> "-"
                ChangeKind.MODIFIED -> "~"
                ChangeKind.MOVED -> "~"
            }
            val basePath = toProjectRelativePath(change.path)
            if (change.kind == ChangeKind.MOVED && !change.originalPath.isNullOrBlank()) {
                val fromPath = toProjectRelativePath(change.originalPath)
                "$symbol $basePath (renamed from $fromPath)"
            } else {
                "$symbol $basePath"
            }
        }

        val remaining = changes.size - previewLimit
        val suffix = if (remaining > 0) "\n...and $remaining more file(s)." else ""

        return """
            This rollback will return the session to:
            $selectedLabel

            It will revert ${changes.size} file change(s):

            $listedChanges$suffix
        """.trimIndent()
    }

    private fun toProjectRelativePath(path: String): String {
        val basePath = project.basePath?.replace("\\", "/") ?: return path.replace("\\", "/")
        val normalizedPath = path.replace("\\", "/")
        return if (normalizedPath.startsWith(basePath)) {
            normalizedPath.removePrefix(basePath).trimStart('/')
        } else {
            normalizedPath
        }
    }

    private fun canCopyTimelinePointOutput(point: RunTimelinePoint): Boolean {
        return when (point.kind) {
            TimelinePointKind.ASSISTANT,
            TimelinePointKind.REASONING,
            TimelinePointKind.TOOL_CALL -> true

            else -> false
        }
    }

    private fun copyTimelinePointOutput(point: RunTimelinePoint, selectedLabel: String) {
        launchBackground {
            val output =
                runCatching { resolveTimelinePointOutput(point) }.getOrNull()

            runInEdt {
                if (output.isNullOrBlank()) {
                    Messages.showErrorDialog(
                        project,
                        "No output found for \"$selectedLabel\".",
                        "Copy Output"
                    )
                    return@runInEdt
                }
                CopyAction.copyToClipboard(output)
            }
        }
    }

    private suspend fun resolveTimelinePointOutput(point: RunTimelinePoint): String? {
        point.outputText?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        if (point.kind == TimelinePointKind.TOOL_CALL) {
            if (point.checkpointRef == null) {
                val sourceMessage = runStateForTimelinePoint(point)?.sourceMessage
                val output = point.toolCallId
                    ?.let { callId -> sourceMessage?.toolCallResults?.get(callId) }
                    ?.trim()
                if (!output.isNullOrBlank()) return output

                return sourceMessage?.toolCallResults
                    ?.values
                    ?.lastOrNull { !it.isNullOrBlank() }
                    ?.trim()
            }

            val checkpointRef = point.checkpointRef
            val index = point.nonSystemMessageCount?.minus(1) ?: return null
            if (index < 0) return null

            val checkpoint = historyService.loadCheckpoint(checkpointRef) ?: return null
            val history = checkpoint.messageHistory.filterNot { it is PromptMessage.System }
            if (index !in history.indices) return null

            val selected = history[index] as? PromptMessage.Tool.Call ?: return null
            val selectedId = selected.id?.takeIf { it.isNotBlank() }

            val outputs = mutableListOf<String>()
            for (nextIndex in index + 1 until history.size) {
                when (val next = history[nextIndex]) {
                    is PromptMessage.Tool.Result -> {
                        val resultId = next.id?.takeIf { it.isNotBlank() }
                        if (selectedId != null) {
                            if (resultId == selectedId) {
                                val output = next.content.trim()
                                if (output.isNotBlank()) outputs.add(output)
                            }
                        } else {
                            val output = next.content.trim()
                            if (output.isNotBlank()) return output
                        }
                    }

                    is PromptMessage.User,
                    is PromptMessage.Assistant,
                    is PromptMessage.Reasoning -> break

                    else -> Unit
                }
            }
            if (outputs.isNotEmpty()) {
                return outputs.joinToString(separator = "\n\n")
            }
        }

        return null
    }

}
