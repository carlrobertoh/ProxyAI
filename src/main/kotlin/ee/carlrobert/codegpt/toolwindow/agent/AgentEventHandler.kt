package ee.carlrobert.codegpt.toolwindow.agent

import ai.koog.agents.core.agent.exception.AIAgentStuckInTheNodeException
import ai.koog.http.client.KoogHttpClientException
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.agent.*
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.agent.rollback.RollbackService
import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.settings.agents.SubagentRuntimeResolver
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTApiException
import ee.carlrobert.codegpt.toolwindow.agent.ui.*
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.*
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.Badge
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.ToolKind
import ee.carlrobert.codegpt.toolwindow.agent.ui.renderer.*
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatMessageResponseBody
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatToolWindowScrollablePanel
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.awt.Component
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JPanel
import kotlin.time.Duration.Companion.milliseconds

class AgentEventHandler(
    private val project: Project,
    private val sessionId: String,
    private val agentApprovalManager: AgentApprovalManager,
    private val approvalContainer: JPanel,
    private val scrollablePanel: ChatToolWindowScrollablePanel,
    private val todoListPanel: TodoListPanel,
    private val userInputPanel: UserInputPanel,
    private val onShowLoading: (String) -> Unit,
    private val onHideLoading: () -> Unit,
    private val onRunFinishedCallback: () -> Unit = {},
    private val onRunCheckpointUpdatedCallback: (UUID, CheckpointRef?) -> Unit = { _, _ -> },
    private val onQueuedMessagePromoted: (MessageWithContext) -> Unit = {}
) : AgentEvents, Disposable {

    companion object {
        val logger: Logger = thisLogger()
    }

    private val mainToolCards = ConcurrentHashMap<String, ToolCallCard>()
    private val pendingToolOutput = ConcurrentHashMap<String, MutableList<ToolOutputLine>>()
    private val scheduledToolOutputFlushes =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val uiRefreshLock = Any()

    private val toolOutputPublisher = ApplicationManager.getApplication()
        .messageBus
        .syncPublisher(AgentToolOutputNotifier.AGENT_TOOL_OUTPUT_TOPIC)

    @Volatile
    private var lastReportedPromptTokens: Long = 0

    private fun keyFor(toolId: String): String = "$sessionId:$toolId"

    @Volatile
    private var currentResponseBody: ChatMessageResponseBody? = null

    @Volatile
    private var lastWriteArgs: WriteTool.Args? = null

    @Volatile
    private var lastEditArgs: EditTool.Args? = null

    @Volatile
    private var currentRollbackRunId: String? = null

    private val approvalQueue: ArrayDeque<ApprovalRequest> = ArrayDeque()

    @Volatile
    private var currentApproval: ApprovalRequest? = null

    private data class QuestionRequest(
        val model: AskUserQuestionTool.AskUserQuestionsModel,
        val deferred: CompletableDeferred<Map<String, String>>
    )

    private val questionQueue: ArrayDeque<QuestionRequest> = ArrayDeque()

    @Volatile
    private var currentQuestion: QuestionRequest? = null
    private var runViewHolder: RunViewHolder? = null

    private val subagentViewHolders = ConcurrentHashMap<String, RunViewHolder>()
    private val serviceScope = DisposableCoroutineScope(Dispatchers.Default)

    @Volatile
    private var uiRefreshScheduled = false

    @Volatile
    private var uiScrollRequested = false

    data class ApprovalRequest(
        var model: ToolApprovalRequest,
        val deferred: CompletableDeferred<Boolean>
    )

    private data class ToolOutputLine(
        val text: String,
        val isError: Boolean
    )

    fun resetForNewSubmission() {
        mainToolCards.clear()
        pendingToolOutput.clear()
        scheduledToolOutputFlushes.clear()
        currentResponseBody = null
        lastWriteArgs = null
        lastEditArgs = null
        currentApproval = null
        approvalQueue.clear()
        currentQuestion = null
        questionQueue.clear()
        clearApprovalContainer()
        todoListPanel.clearTodos()
        runViewHolder = null
        subagentViewHolders.clear()
        lastReportedPromptTokens = 0
        currentRollbackRunId = null
    }

    private fun clearApprovalContainer() {
        approvalContainer.removeAll()
        approvalContainer.isVisible = false
        approvalContainer.revalidate()
        approvalContainer.repaint()
    }

    private fun monitorBackgroundProcessOutput(
        bgId: String,
        toolId: String,
        onComplete: (() -> Unit)? = null
    ) {
        serviceScope.launch {
            try {
                var outPos = 0
                var errPos = 0
                while (true) {
                    val po = BackgroundProcessManager.getOutput(bgId) ?: break
                    val stdout = po.stdout.toString()
                    val stderr = po.stderr.toString()
                    if (outPos < stdout.length) {
                        stdout.substring(outPos).split('\n').forEach { line ->
                            if (line.isNotEmpty()) toolOutputPublisher.toolOutput(
                                toolId,
                                line,
                                false
                            )
                        }
                        outPos = stdout.length
                    }
                    if (errPos < stderr.length) {
                        stderr.substring(errPos).split('\n').forEach { line ->
                            if (line.isNotEmpty()) toolOutputPublisher.toolOutput(
                                toolId,
                                line,
                                true
                            )
                        }
                        errPos = stderr.length
                    }
                    if (po.isComplete) break
                    delay(300.milliseconds)
                }
            } catch (ex: Exception) {
                logger.warn("Failed to monitor background process output", ex)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun setCurrentResponseBody(responseBody: ChatMessageResponseBody) {
        currentResponseBody = responseBody
    }

    fun setCurrentRollbackRunId(runId: String?) {
        currentRollbackRunId = runId
    }

    override fun onAgentCompleted(agentId: String) {
        runCatching {
            project.service<AgentToolWindowContentManager>().getTabbedPane()
                .onAgentCompleted(sessionId)
        }
        handleDone()
    }

    override fun onAgentException(provider: ServiceType, throwable: Throwable) {
        val statusCode = extractStatusCode(throwable)
        if (statusCode != null) {
            handleHttpException(provider, statusCode, throwable)
            return
        }

        currentResponseBody?.displayError(extractDisplayMessage(throwable))
        handleDone()
    }

    private fun handleHttpException(provider: ServiceType, statusCode: Int, throwable: Throwable) {
        when (statusCode) {
            401 -> {
                currentResponseBody?.displayMissingCredential()
                handleDone()
            }

            403 -> {
                currentResponseBody?.displayForbidden()
                handleDone()
            }

            429 -> {
                if (provider == ServiceType.PROXYAI) {
                    currentResponseBody?.displayCreditsExhausted()
                } else {
                    currentResponseBody?.displayError(extractDisplayMessage(throwable))
                }
                handleDone()
            }

            else -> {
                if (provider == ServiceType.PROXYAI) {
                    currentResponseBody?.displayError(CodeGPTBundle.get("toolwindow.agent.error.generic"))
                } else {
                    currentResponseBody?.displayError(extractDisplayMessage(throwable))
                }
                handleDone()
            }
        }
    }

    private fun extractStatusCode(throwable: Throwable): Int? {
        return generateSequence(throwable) { it.cause }
            .take(10)
            .firstNotNullOfOrNull { cause ->
                when (cause) {
                    is KoogHttpClientException -> cause.statusCode
                    is CodeGPTApiException -> cause.status.takeIf { it > 0 }
                    else -> null
                }
            }
    }

    private fun extractDisplayMessage(throwable: Throwable): String {
        val causes = generateSequence(throwable) { it.cause }.take(10).toList()
        if (causes.any { it is AIAgentStuckInTheNodeException }) {
            return CodeGPTBundle.get("toolwindow.agent.error.executionFailed")
        }

        val message = causes.firstNotNullOfOrNull { cause ->
            when (cause) {
                is CodeGPTApiException -> cause.detail?.takeIf { it.isNotBlank() } ?: cause.title
                is KoogHttpClientException -> cause.errorBody?.takeIf { it.isNotBlank() }
                    ?: cause.message

                else -> cause.message
            }?.trim()?.takeIf { it.isNotEmpty() }
        } ?: CodeGPTBundle.get("toolwindow.agent.error.generic")

        return if (message.length > 400) "${message.take(400)}..." else message
    }

    private fun handleDone() {
        runInEdt {
            currentRollbackRunId?.let { runId ->
                project.service<RollbackService>().finishRun(runId)
            } ?: project.service<RollbackService>().finishSession(sessionId)
            currentRollbackRunId = null
            currentResponseBody?.finishThinking()
            onRunFinishedCallback()
            onHideLoading()
            userInputPanel.setStopEnabled(false)
            requestUiRefresh()
            todoListPanel.clearTodos()
            runCatching {
                project.service<AgentToolWindowContentManager>()
                    .setTabStatus(sessionId, AgentToolWindowTabbedPane.TabStatus.STOPPED)
            }
        }
    }

    override suspend fun approveToolCall(request: ToolApprovalRequest): Boolean {
        val isWrite = request.type == ToolApprovalType.WRITE
        val isEdit = request.type == ToolApprovalType.EDIT

        if (isWrite || isEdit) {
            val deferred = CompletableDeferred<Boolean>()
            val resolvedRequest = if (isEdit && request.payload == null) {
                val payload = lastEditArgs?.let { args ->
                    EditPayload(
                        filePath = args.filePath,
                        oldString = args.oldString,
                        newString = args.newString,
                        replaceAll = args.replaceAll,
                        proposedContent = null
                    )
                }
                if (payload != null) request.copy(payload = payload) else request
            } else {
                request
            }
            logger.debug(
                "Enqueueing agent approval for session=$sessionId type=${resolvedRequest.type} title=${resolvedRequest.title.logPreview()} payload=${resolvedRequest.payload.logSummary()}"
            )
            runInEdt {
                approvalQueue.addLast(ApprovalRequest(resolvedRequest, deferred))
                maybeShowNextApproval()
            }

            lastWriteArgs?.let {
                if (isWrite) agentApprovalManager.openWriteApprovalDiff(it, deferred)
            }
            lastEditArgs?.let { args ->
                if (isEdit) {
                    val proposed = when (val payload = resolvedRequest.payload) {
                        is EditPayload -> payload.proposedContent
                        else -> null
                    }
                    agentApprovalManager.openEditApprovalDiff(args, deferred, proposed)
                }
            }
            return deferred.await()
        }

        val decision = CompletableDeferred<Boolean>()
        logger.debug(
            "Enqueueing agent approval for session=$sessionId type=${request.type} title=${request.title.logPreview()} payload=${request.payload.logSummary()}"
        )
        runInEdt {
            approvalQueue.addLast(ApprovalRequest(request, decision))
            maybeShowNextApproval()
        }
        return decision.await()
    }

    override suspend fun askUserQuestions(
        model: AskUserQuestionTool.AskUserQuestionsModel
    ): Map<String, String> {
        val deferred = CompletableDeferred<Map<String, String>>()
        runInEdt {
            questionQueue.addLast(QuestionRequest(model, deferred))
            maybeShowNextQuestion()
        }
        return deferred.await()
    }

    override fun onTextReceived(text: String) {
        runInEdt {
            currentResponseBody?.updateMessage(text)
            requestUiRefresh()
        }
    }

    override fun onThinkingReceived(text: String) {
        runInEdt {
            currentResponseBody?.appendThinking(text)
            requestUiRefresh()
        }
    }

    override fun onPlanUpdated(entries: List<PlanEntry>) {
        runInEdt {
            todoListPanel.updateTodos(entries.toTodoItems())
            todoListPanel.isVisible = entries.isNotEmpty()
            requestUiRefresh()
        }
    }

    override fun onToolStarting(id: String, toolName: String, args: Any?) {
        val uiArgs = ToolSpecs.coerceArgsForUi(toolName, args)
        if (toolName == "Tool" && uiArgs == null) {
            logger.debug("Deferring placeholder tool card session=$sessionId toolId=$id toolName=$toolName")
            return
        }

        when (uiArgs) {
            is TodoWriteTool.Args -> {
                runInEdt {
                    val inProgressTask =
                        uiArgs.todos.find { it.status == TodoWriteTool.TodoStatus.IN_PROGRESS }
                    if (inProgressTask != null) {
                        onShowLoading(inProgressTask.activeForm)
                    }
                    todoListPanel.updateTodos(uiArgs.todos)
                    todoListPanel.isVisible = true
                    requestUiRefresh()
                }
            }

            is TaskTool.Args -> {
                runInEdt {
                    val host = ensureRunViewForSubagent(id)
                    host.addEntry(createTaskEntry(id, null, uiArgs))
                    host.refresh()
                    requestUiRefresh()
                }
            }

            else -> {
                when (uiArgs) {
                    is EditTool.Args -> {
                        trackEditOperation(uiArgs)
                    }

                    is WriteTool.Args -> {
                        trackWriteOperation(uiArgs)
                    }
                }

                runInEdt {
                    val key = keyFor(id)
                    val existingCard = mainToolCards[key]
                    if (existingCard == null) {
                        val card = ToolCallCard(project, toolName, uiArgs)
                        val descriptor = card.getDescriptor()
                        if (descriptor.kind == ToolKind.OTHER || descriptor.titleMain.isBlank()) {
                            logger.warn(
                                "Created generic tool card session=$sessionId toolId=$id toolName=$toolName argsType=${uiArgs?.javaClass?.name ?: "null"} title=${descriptor.titleMain}"
                            )
                        }
                        mainToolCards[key] = card
                        currentResponseBody?.addToolStatusPanel(card)
                        requestUiRefresh()
                    } else {
                        logger.debug(
                            "Updated tool card session=$sessionId toolId=$id toolName=$toolName argsType=${uiArgs?.javaClass?.name ?: "null"} title=${existingCard.getDescriptor().titleMain}"
                        )
                        requestUiRefresh(false)
                    }
                }
            }
        }
    }

    override fun onToolCompleted(id: String?, toolName: String, result: Any?) {
        val uiResult = ToolSpecs.coerceResultForUi(toolName, result)
        runInEdt {
            if (id != null && (toolName == "Task" || uiResult is TaskTool.Result)) {
                val holder = runViewHolder ?: subagentViewHolders.values.firstOrNull { viewHolder ->
                    viewHolder.getItems().any { entry -> entry.id == id }
                }
                holder?.completeEntry(id, uiResult)
                holder?.refresh()
            } else if (id != null && mainToolCards.containsKey(keyFor(id))) {
                val success = uiResult !is ToolError && uiResult != null
                mainToolCards[keyFor(id)]?.complete(success, uiResult)
                mainToolCards[keyFor(id)]?.getDescriptor()?.let { descriptor ->
                    if (descriptor.kind == ToolKind.OTHER || descriptor.titleMain.isBlank()) {
                        logger.warn(
                            "Completed generic tool card session=$sessionId toolId=$id toolName=$toolName resultType=${uiResult?.javaClass?.name ?: "null"} title=${descriptor.titleMain}"
                        )
                    }
                }

                val bgId = (uiResult as? BashTool.Result)?.bashId
                if (bgId == null) {
                    mainToolCards.remove(keyFor(id))
                } else {
                    monitorBackgroundProcessOutput(bgId, id) {
                        runInEdt {
                            mainToolCards.remove(keyFor(id))
                        }
                    }
                }
            }
            requestUiRefresh()
        }
    }

    override fun onSubAgentToolStarting(parentId: String, toolName: String, args: Any?): String {
        val cid = UUID.randomUUID().toString()

        if (args is TodoWriteTool.Args) {
            val inProgressTask =
                args.todos.find { it.status == TodoWriteTool.TodoStatus.IN_PROGRESS }
            if (inProgressTask != null) {
                onShowLoading(inProgressTask.activeForm)
            }
            return cid
        }

        runInEdt {
            val host = ensureRunViewForSubagent(parentId)
            val entry = when (args) {
                is ReadTool.Args -> RunEntry.ReadEntry(cid, parentId, args, null)
                is IntelliJSearchTool.Args -> RunEntry.IntelliJSearchEntry(
                    cid,
                    parentId,
                    args,
                    null
                )

                is BashTool.Args -> RunEntry.BashEntry(cid, parentId, args, null)
                is BashOutputTool.Args -> RunEntry.BashEntry(cid, parentId, null, null)
                is KillShellTool.Args -> RunEntry.OtherEntry(
                    cid,
                    parentId,
                    "Kill Bash (${args.bashId.take(6)})"
                )

                is WebSearchTool.Args -> RunEntry.WebEntry(cid, parentId, args, null)
                is WebFetchTool.Args -> RunEntry.WebFetchEntry(cid, parentId, args, null)
                is GetLibraryDocsTool.Args -> RunEntry.LibraryDocsEntry(cid, parentId, args, null)
                is ResolveLibraryIdTool.Args -> RunEntry.LibraryResolveEntry(
                    cid,
                    parentId,
                    args,
                    null
                )

                is WriteTool.Args -> {
                    lastWriteArgs = args
                    RunEntry.WriteEntry(cid, parentId, args, null)
                }

                is EditTool.Args -> {
                    lastEditArgs = args
                    RunEntry.EditEntry(cid, parentId, args, null)
                }

                is TaskTool.Args -> createTaskEntry(cid, parentId, args)
                is McpTool.Args -> RunEntry.McpEntry(cid, parentId, args, null)

                else -> RunEntry.OtherEntry(cid, parentId, toolName)
            }
            host.addEntry(entry)
            host.refresh()
            requestUiRefresh()
        }
        return cid
    }

    override fun onSubAgentToolCompleted(
        parentId: String,
        childId: String?,
        toolName: String,
        result: Any?
    ) {
        runInEdt {
            if (childId != null) {
                val holder = subagentViewHolders.values.find { viewHolder ->
                    viewHolder.getItems().any { entry -> entry.id == childId }
                } ?: runViewHolder
                holder?.completeEntry(childId, result)
                holder?.refresh()

                val bgId = (result as? BashTool.Result)?.bashId
                if (bgId != null) {
                    monitorBackgroundProcessOutput(bgId, childId)
                }
            }
            requestUiRefresh()
        }
    }

    private fun List<PlanEntry>.toTodoItems(): List<TodoWriteTool.TodoItem> {
        return map { entry ->
            TodoWriteTool.TodoItem(
                content = entry.content,
                status = when (entry.status) {
                    PlanEntryStatus.PENDING -> TodoWriteTool.TodoStatus.PENDING
                    PlanEntryStatus.IN_PROGRESS -> TodoWriteTool.TodoStatus.IN_PROGRESS
                    PlanEntryStatus.COMPLETED -> TodoWriteTool.TodoStatus.COMPLETED
                },
                activeForm = entry.content
            )
        }
    }

    override fun onQueuedMessagesResolved(message: MessageWithContext?) {
        val pendingMessage = message
            ?: project.service<AgentService>()
                .getPendingMessages(sessionId)
                .firstOrNull { it.uiVisible }
            ?: return
        logger.debug(
            "Resolving queued message in UI for session=$sessionId messageId=${pendingMessage.id} uiVisible=${pendingMessage.uiVisible} preview=${pendingMessage.text.logPreview()}"
        )
        onQueuedMessagePromoted(pendingMessage)
    }

    override fun onRunCheckpointUpdated(runMessageId: UUID, ref: CheckpointRef?) {
        runInEdt {
            onRunCheckpointUpdatedCallback(runMessageId, ref)
        }
    }

    override fun onTokenUsageAvailable(tokenUsage: Long) {
        onUsageAvailable(AgentUsageEvent(usedTokens = tokenUsage))
    }

    override fun onUsageAvailable(event: AgentUsageEvent) {
        lastReportedPromptTokens = event.usedTokens
        project.messageBus.syncPublisher(TokenUsageListener.TOKEN_USAGE_TOPIC)
            .onTokenUsageChanged(
                TokenUsageEvent(
                    sessionId = sessionId,
                    totalTokens = event.usedTokens,
                    sizeTokens = event.sizeTokens,
                    costAmount = event.costAmount,
                    costCurrency = event.costCurrency
                )
            )
    }

    override fun onRuntimeOptionsUpdated() {
        runInEdt {
            userInputPanel.refreshModelDependentState()
        }
    }

    override fun onSessionInfoUpdated(title: String?, updatedAt: String?) {
        val normalizedTitle = title?.trim().orEmpty()
        if (normalizedTitle.isEmpty()) {
            return
        }

        val contentManager = project.service<AgentToolWindowContentManager>()
        val session = contentManager.getSession(sessionId) ?: return
        val previousExternalTitle = session.externalAgentSessionTitle
        session.externalAgentSessionTitle = normalizedTitle

        val shouldRename = session.displayName.isBlank() ||
                session.displayName == previousExternalTitle ||
                session.displayName.matches(Regex("""Agent \d+( \(\d+\))?"""))

        if (shouldRename) {
            project.messageBus.syncPublisher(AgentTabTitleNotifier.AGENT_TAB_TITLE_TOPIC)
                .updateTabTitle(sessionId, normalizedTitle)
        }
    }

    override fun onCreditsAvailable(event: AgentCreditsEvent) {
        project.messageBus.syncPublisher(AgentCreditsListener.AGENT_CREDITS_TOPIC)
            .onCreditsChanged(event)
    }

    override fun onRetry(attempt: Int, maxAttempts: Int) {
        runInEdt {
            onShowLoading(CodeGPTBundle.get("toolwindow.agent.retrying", attempt, maxAttempts))
        }
    }

    override fun onHistoryCompressionStateChanged(isCompressing: Boolean) {
        val key =
            if (isCompressing) "toolwindow.chat.compressingHistory" else "toolwindow.chat.loading"
        runInEdt {
            onShowLoading(CodeGPTBundle.get(key))
        }
    }

    private fun createTaskEntry(
        id: String,
        parentId: String?,
        args: TaskTool.Args
    ): RunEntry.TaskEntry {
        val session = project.service<AgentToolWindowContentManager>().getSession(sessionId)
        val runtimeLabel = SubagentRuntimeResolver.resolveForSession(
            project = project,
            subagentType = args.subagentType,
            parentProvider = session?.serviceType,
            parentModelCode = session?.modelCode,
            taskModelOverride = args.model
        )?.displayLabel
        return RunEntry.TaskEntry(
            id = id,
            parentId = parentId,
            args = args,
            result = null,
            summary = TaskSummary(
                toolCalls = 0,
                tokens = 0,
                runtimeLabel = runtimeLabel
            )
        )
    }

    private fun maybeShowNextApproval() {
        if (currentApproval != null) return
        val next = approvalQueue.pollFirst() ?: run {
            clearApprovalContainer()
            maybeShowNextQuestion()
            return
        }

        val contentManager = project.service<AgentToolWindowContentManager>()
        if (contentManager.isSessionAutoApproved(sessionId)) {
            logger.debug(
                "Auto-approving queued agent approval for session=$sessionId type=${next.model.type} title=${next.model.title.logPreview()}"
            )
            next.deferred.complete(true)
            currentApproval = null
            maybeShowNextApproval()
            return
        }

        currentApproval = next
        if (next.model.type == ToolApprovalType.WRITE && next.model.payload == null) {
            lastWriteArgs?.let { wa ->
                next.model = next.model.copy(payload = WritePayload(wa.filePath, wa.content))
            }
        } else if (next.model.type == ToolApprovalType.EDIT && next.model.payload == null) {
            lastEditArgs?.let { ea ->
                next.model = next.model.copy(
                    payload = EditPayload(
                        ea.filePath,
                        ea.oldString,
                        ea.newString,
                        ea.replaceAll
                    )
                )
            }
        }

        if (next.model.type == ToolApprovalType.EDIT) {
            updateEditToolCardPreview(next.model)
        }

        logger.debug(
            "Showing agent approval for session=$sessionId type=${next.model.type} title=${next.model.title.logPreview()} payload=${next.model.payload.logSummary()} queueRemaining=${approvalQueue.size}"
        )

        runCatching {
            project.service<AgentToolWindowContentManager>()
                .setTabStatus(sessionId, AgentToolWindowTabbedPane.TabStatus.APPROVAL)
        }

        val panel = DefaultApprovalPanelFactory.create(
            project,
            next.model,
            onApprove = { auto ->
                if (auto) {
                    project.service<AgentToolWindowContentManager>()
                        .markSessionAsAutoApproved(sessionId)
                }

                logger.debug(
                    "Approved agent approval for session=$sessionId type=${next.model.type} auto=$auto title=${next.model.title.logPreview()}"
                )
                next.deferred.complete(true)
                currentApproval = null
                clearApprovalContainer()
                runCatching {
                    project.service<AgentToolWindowContentManager>()
                        .setTabStatus(sessionId, AgentToolWindowTabbedPane.TabStatus.RUNNING)
                }
                maybeShowNextApproval()
            },
            onReject = {
                logger.debug(
                    "Rejected agent approval for session=$sessionId type=${next.model.type} title=${next.model.title.logPreview()}"
                )
                next.deferred.complete(false)
                currentApproval = null
                clearApprovalContainer()

                try {
                    project.service<AgentService>().cancelCurrentRun(sessionId)
                } catch (_: Exception) {
                }
                handleDone()
            }
        )
        approvalContainer.removeAll()
        panel.alignmentX = Component.LEFT_ALIGNMENT
        approvalContainer.add(panel)
        approvalContainer.isVisible = true
        approvalContainer.revalidate()
        approvalContainer.repaint()
    }

    private fun updateEditToolCardPreview(request: ToolApprovalRequest) {
        val payload = request.payload ?: return
        val (path, before, after) = when (payload) {
            is EditPayload -> {
                val currentContent = getFileContentWithFallback(payload.filePath)
                val proposed = payload.proposedContent ?: applyStringReplacement(
                    currentContent,
                    payload.oldString,
                    payload.newString,
                    payload.replaceAll
                )
                Triple(payload.filePath, currentContent, proposed)
            }

            else -> return
        }

        val (inserted, deleted, changed) = lineDiffStats(before, after)
        val texts = diffBadgeText(inserted, deleted, changed)
        val diffBadges = listOf(
            Badge(texts.inserted, ChangeColors.inserted),
            Badge(texts.deleted, ChangeColors.deleted),
            Badge(texts.changed, ChangeColors.modified)
        )

        val card = mainToolCards.values.firstOrNull { candidate ->
            val descriptor = candidate.getDescriptor()
            descriptor.kind == ToolKind.EDIT && descriptor.fileLink?.path == path
        } ?: return

        card.updateDescriptor { descriptor ->
            val nonDiffBadges = descriptor.secondaryBadges.filterNot { isDiffBadge(it) }
            descriptor.copy(
                secondaryBadges = nonDiffBadges + diffBadges,
                summary = null
            )
        }
    }

    private fun isDiffBadge(badge: Badge): Boolean {
        val text = badge.text
        return text.startsWith("[+") || text.startsWith("[-") || text.startsWith("[~")
    }

    private fun maybeShowNextQuestion() {
        if (currentApproval != null || currentQuestion != null) return
        val next = questionQueue.pollFirst() ?: return

        currentQuestion = next

        runCatching {
            project.service<AgentToolWindowContentManager>()
                .setTabStatus(sessionId, AgentToolWindowTabbedPane.TabStatus.APPROVAL)
        }

        val panel = AskUserQuestionPanel(
            model = next.model,
            onSubmit = { answers ->
                next.deferred.complete(answers)
                currentQuestion = null
                clearApprovalContainer()
                runCatching {
                    project.service<AgentToolWindowContentManager>()
                        .setTabStatus(sessionId, AgentToolWindowTabbedPane.TabStatus.RUNNING)
                }
                maybeShowNextApproval()
                maybeShowNextQuestion()
            },
            onCancel = {
                next.deferred.complete(emptyMap())
                currentQuestion = null
                clearApprovalContainer()
                runCatching {
                    project.service<AgentToolWindowContentManager>()
                        .setTabStatus(sessionId, AgentToolWindowTabbedPane.TabStatus.RUNNING)
                }
                maybeShowNextApproval()
                maybeShowNextQuestion()
            }
        )
        approvalContainer.removeAll()
        panel.alignmentX = Component.LEFT_ALIGNMENT
        approvalContainer.add(panel)
        approvalContainer.isVisible = true
        approvalContainer.revalidate()
        approvalContainer.repaint()
    }

    private fun ensureRunViewForSubagent(taskId: String): RunViewHolder {
        val existing = subagentViewHolders[taskId]
        if (existing != null) return existing

        val vm = AgentRunViewModel()
        val view = AgentRunDslPanel(project, vm)
        currentResponseBody?.addToolStatusPanel(view.component)
        val viewHolder = RunViewHolder(vm, view)
        subagentViewHolders[taskId] = viewHolder
        return viewHolder
    }

    private fun String.logPreview(limit: Int = 120): String {
        return replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(limit)
    }

    private fun ToolApprovalPayload?.logSummary(): String {
        return when (this) {
            is WritePayload -> "write:${filePath.logPreview(80)}"
            is EditPayload -> "edit:${filePath.logPreview(80)}"
            is BashPayload -> "bash:${command.logPreview(80)}"
            null -> "none"
        }
    }

    class RunViewHolder(
        private val vm: AgentRunViewModel,
        private val view: AgentRunDslPanel,
    ) {
        fun addEntry(entry: RunEntry) {
            vm.addEntry(entry)
            view.refresh()
        }

        fun completeEntry(id: String, result: Any?) {
            vm.completeEntry(id, result)
            view.refresh()
        }

        fun refresh() = view.refresh()

        fun getItems(): List<RunEntry> {
            return vm.items
        }

        fun appendStreamingLine(id: String, text: String, isError: Boolean): Boolean {
            return view.appendStreaming(id, text, isError)
        }
    }

    fun handleToolOutput(toolId: String, text: String, isError: Boolean) {
        val lines = pendingToolOutput.computeIfAbsent(toolId) {
            Collections.synchronizedList(mutableListOf())
        }
        lines.add(ToolOutputLine(text, isError))
        if (!scheduledToolOutputFlushes.add(toolId)) {
            return
        }
        runInEdt { flushToolOutput(toolId) }
    }

    override fun dispose() {
        serviceScope.dispose()
        agentApprovalManager.dispose()
        mainToolCards.clear()
        pendingToolOutput.clear()
        scheduledToolOutputFlushes.clear()
        approvalQueue.clear()
        subagentViewHolders.clear()
    }

    private fun flushToolOutput(toolId: String) {
        scheduledToolOutputFlushes.remove(toolId)
        val lines = pendingToolOutput.remove(toolId).orEmpty()
        if (lines.isEmpty()) {
            return
        }
        var handled = false
        mainToolCards[toolId]?.let { card ->
            lines.forEach { line -> card.appendStreamingLine(line.text, line.isError) }
            handled = true
        }
        if (!handled) {
            val rawId =
                if (toolId.startsWith("$sessionId:")) toolId.substringAfter(":") else toolId
            subagentViewHolders.values.firstOrNull { holder ->
                var appended = false
                lines.forEach { line ->
                    appended =
                        holder.appendStreamingLine(rawId, line.text, line.isError) || appended
                }
                appended
            }
        }
        requestUiRefresh()
    }

    private fun requestUiRefresh(scrollToBottom: Boolean = true) {
        val shouldSchedule = synchronized(uiRefreshLock) {
            uiScrollRequested = uiScrollRequested || scrollToBottom
            if (uiRefreshScheduled) {
                false
            } else {
                uiRefreshScheduled = true
                true
            }
        }
        if (!shouldSchedule) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            val shouldScroll = synchronized(uiRefreshLock) {
                uiRefreshScheduled = false
                uiScrollRequested.also { uiScrollRequested = false }
            }
            scrollablePanel.update()
            if (shouldScroll) {
                scrollablePanel.scrollToBottom()
            }
        }
    }

    private fun trackEditOperation(args: EditTool.Args) {
        lastEditArgs = args
        val normalizedPath = args.filePath.replace("\\", "/")
        val originalContent = runCatching {
            val vf = LocalFileSystem.getInstance().findFileByPath(normalizedPath)
            val documentText = vf?.let { file ->
                runReadActionBlocking { FileDocumentManager.getInstance().getDocument(file)?.text }
            }
            documentText ?: java.io.File(normalizedPath).readText(Charsets.UTF_8)
        }.getOrNull() ?: ""
        val rollbackService = project.service<RollbackService>()
        val runId = currentRollbackRunId
        if (runId != null) {
            rollbackService.trackEditForRun(runId, normalizedPath, originalContent)
        } else {
            rollbackService.trackEdit(sessionId, normalizedPath, originalContent)
        }
    }

    private fun trackWriteOperation(args: WriteTool.Args) {
        lastWriteArgs = args
        val normalizedPath = args.filePath.replace("\\", "/")
        val rollbackService = project.service<RollbackService>()
        val runId = currentRollbackRunId
        if (runId != null) {
            rollbackService.trackWriteForRun(runId, normalizedPath)
        } else {
            rollbackService.trackWrite(sessionId, normalizedPath)
        }
    }
}
