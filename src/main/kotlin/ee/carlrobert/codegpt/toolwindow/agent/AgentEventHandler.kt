package ee.carlrobert.codegpt.toolwindow.agent

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.agent.*
import ee.carlrobert.codegpt.agent.rollback.RollbackService
import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.conversations.message.TokenUsage
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.ui.*
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.*
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatMessageResponseBody
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatToolWindowScrollablePanel
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import kotlinx.coroutines.*
import java.awt.Component
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JPanel

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
    private val onQueuedMessagesResolved: (MessageWithContext) -> Unit = {}
) : AgentEvents, Disposable {

    companion object {
        val logger = thisLogger()
    }

    private val mainToolCards = ConcurrentHashMap<String, ToolCallCard>()

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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class ApprovalRequest(
        var model: ToolApprovalRequest,
        val deferred: CompletableDeferred<Boolean>
    )

    fun resetForNewSubmission() {
        mainToolCards.clear()
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
                    delay(300)
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

    override fun onAgentCompleted(agentId: String) {
        runCatching {
            project.service<AgentToolWindowContentManager>().getTabbedPane()
                .onAgentCompleted(sessionId)
        }
        handleDone()
    }

    override fun onClientException(provider: ServiceType, ex: LLMClientException) {
        val cause = ex.cause
        if (cause is KoogHttpClientException) {
            if (provider == ServiceType.PROXYAI) {
                handleProxyAIException(cause)
            }
        }
    }

    private fun handleProxyAIException(ex: KoogHttpClientException) {
        when (ex.statusCode) {
            401 -> {
                currentResponseBody?.displayMissingCredential()
                handleDone()
            }

            403 -> {
                currentResponseBody?.displayForbidden()
                handleDone()
            }

            429 -> {
                currentResponseBody?.displayCreditsExhausted()
                handleDone()
            }
        }
    }

    private fun handleDone() {
        runInEdt {
            project.service<RollbackService>().finishSession(sessionId)
            currentResponseBody?.finishThinking()
            onHideLoading()
            userInputPanel.setStopEnabled(false)
            scrollablePanel.update()
            scrollablePanel.scrollToBottom()
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
            runInEdt {
                approvalQueue.addLast(ApprovalRequest(request, deferred))
                maybeShowNextApproval()
            }

            lastWriteArgs?.let {
                if (isWrite) agentApprovalManager.openWriteApprovalDiff(it, deferred)
            }
            lastEditArgs?.let {
                if (isEdit) agentApprovalManager.openEditApprovalDiff(it, deferred)
            }
            return deferred.await()
        }

        val decision = CompletableDeferred<Boolean>()
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
            val cleanedText =
                text.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            currentResponseBody?.updateMessage(cleanedText)
            scrollablePanel.update()
            scrollablePanel.scrollToBottom()
        }
    }

    override fun onToolStarting(id: String, toolName: String, args: Any?) {
        when (args) {
            is TodoWriteTool.Args -> {
                runInEdt {
                    val inProgressTask =
                        args.todos.find { it.status == TodoWriteTool.TodoStatus.IN_PROGRESS }
                    if (inProgressTask != null) {
                        onShowLoading(inProgressTask.activeForm)
                    }
                    todoListPanel.updateTodos(args.todos)
                    todoListPanel.isVisible = true
                    scrollablePanel.update()
                    scrollablePanel.scrollToBottom()
                }
            }

            is TaskTool.Args -> {
                runInEdt {
                    val host = ensureRunViewForSubagent(id)
                    host.addEntry(RunEntry.TaskEntry(id, null, args, null))
                    host.refresh()
                    scrollablePanel.update()
                    scrollablePanel.scrollToBottom()
                }
            }

            else -> {
                if (args is WriteTool.Args) {
                    lastWriteArgs = args
                } else if (args is EditTool.Args) {
                    lastEditArgs = args
                }

                if (toolName != "Task") {
                    runInEdt {
                        val key = keyFor(id)
                        if (!mainToolCards.containsKey(key)) {
                            val card = ToolCallCard(project, toolName, args)
                            mainToolCards[key] = card
                            currentResponseBody?.addToolStatusPanel(card)
                            scrollablePanel.update()
                            scrollablePanel.scrollToBottom()
                        }
                    }
                }
            }
        }
    }

    override fun onToolCompleted(id: String?, toolName: String, result: Any?) {
        runInEdt {
            if (id != null && (toolName == "Task" || result is TaskTool.Result)) {
                val holder = runViewHolder ?: subagentViewHolders.values.firstOrNull { viewHolder ->
                    viewHolder.getItems().any { entry -> entry.id == id }
                }
                holder?.completeEntry(id, result)
                holder?.refresh()
            } else if (id != null && mainToolCards.containsKey(keyFor(id))) {
                val success = result !is ToolError && result != null
                mainToolCards[keyFor(id)]?.complete(success, result)

                val bgId = (result as? BashTool.Result)?.bashId
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
            scrollablePanel.update()
            scrollablePanel.scrollToBottom()
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

                is TaskTool.Args -> RunEntry.TaskEntry(cid, parentId, args, null)

                else -> RunEntry.OtherEntry(cid, parentId, toolName)
            }
            host.addEntry(entry)
            host.refresh()
            scrollablePanel.update()
            scrollablePanel.scrollToBottom()
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
            scrollablePanel.update()
            scrollablePanel.scrollToBottom()
        }
    }

    override fun onQueuedMessagesResolved() {
        val pendingMessage =
            project.service<AgentService>().getPendingMessages(sessionId).firstOrNull() ?: return
        onQueuedMessagesResolved(pendingMessage)
    }

    override fun onTokenUsageAvailable(tokenUsage: Long) {
        val tracker = project.service<AgentService>().getTokenTrackerForSession(sessionId)
        if (tokenUsage < lastReportedPromptTokens) {
            tracker.setPromptTokens(tokenUsage)
        } else {
            val delta = (tokenUsage - lastReportedPromptTokens).coerceAtLeast(0)
            if (delta > 0) {
                tracker.addPromptTokens(delta)
            }
        }
        lastReportedPromptTokens = tokenUsage

        val event = TokenUsageEvent(sessionId, tracker.getPromptTokens())
        project.messageBus.syncPublisher(TokenUsageListener.TOKEN_USAGE_TOPIC)
            .onTokenUsageChanged(event)
    }

    override fun onTokenUsageUpdated(tokenUsage: TokenUsage) {
        val tracker = project.service<AgentService>().getTokenTrackerForSession(sessionId)
        tracker.updateTokenUsage(tokenUsage)
        val event = TokenUsageEvent(sessionId, tracker.getPromptTokens())
        project.messageBus.syncPublisher(TokenUsageListener.TOKEN_USAGE_TOPIC)
            .onTokenUsageChanged(event)
    }

    override fun onCreditsAvailable(event: AgentCreditsEvent) {
        project.messageBus.syncPublisher(AgentCreditsListener.AGENT_CREDITS_TOPIC)
            .onCreditsChanged(event)
    }

    override fun onHistoryCompressionStateChanged(isCompressing: Boolean) {
        val key =
            if (isCompressing) "toolwindow.chat.compressingHistory" else "toolwindow.chat.loading"
        runInEdt {
            onShowLoading(CodeGPTBundle.get(key))
        }
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
        runInEdt {
            var handled = false
            mainToolCards[toolId]?.let {
                it.appendStreamingLine(text, isError)
                handled = true
            }
            if (!handled) {
                val rawId =
                    if (toolId.startsWith("$sessionId:")) toolId.substringAfter(":") else toolId
                subagentViewHolders.values.firstOrNull { holder ->
                    holder.appendStreamingLine(rawId, text, isError)
                }
            }
        }
    }

    override fun dispose() {
        mainToolCards.clear()
        approvalQueue.clear()
        subagentViewHolders.clear()
    }
}
