package ee.carlrobert.codegpt.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.completions.CompletionResponseEventListener
import ee.carlrobert.codegpt.completions.ToolApprovalMode
import ee.carlrobert.codegpt.completions.ToolwindowChatCompletionRequestHandler
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.toolwindow.ui.mcp.McpStatusPanel
import ee.carlrobert.codegpt.toolwindow.ui.mcp.ToolGroupPanel
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.client.openai.completion.response.ToolCall
import ee.carlrobert.llm.client.openai.completion.response.ToolFunctionResponse
import ee.carlrobert.llm.completion.CompletionEventListener
import okhttp3.sse.EventSource
import java.util.concurrent.CompletableFuture
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

class McpToolCallEventListener(
    private val project: Project,
    private val callParameters: ChatCompletionParameters,
    private val wrappedListener: CompletionResponseEventListener,
    private val onToolCallUIUpdate: (JPanel) -> Unit,
    private val requestHandler: ToolwindowChatCompletionRequestHandler
) : CompletionEventListener<String> {

    companion object {
        private val logger = thisLogger()
    }

    private val objectMapper = ObjectMapper()
    private val toolCallHandler = McpToolCallHandler.getInstance(project)
    private val sessionManager = service<McpSessionManager>()
    private val messageBuilder = StringBuilder()
    private val aggregator = ToolCallAggregator()
    private var isProcessingToolCalls = false
    private val activeStatusPanels = mutableMapOf<String, McpStatusPanel>()
    private val mcpToolsMap = mutableMapOf<String, McpTool>()

    init {
        callParameters.mcpTools?.forEach { tool ->
            mcpToolsMap[tool.name] = tool
        }
    }

    override fun onOpen() {
        wrappedListener.handleRequestOpen()
    }

    override fun onToolCall(toolCall: ToolCall) {
        isProcessingToolCalls = true
        aggregator.add(toolCall)
    }

    override fun onMessage(message: String, eventSource: EventSource) {
        messageBuilder.append(message)
        wrappedListener.handleMessage(message)
    }

    override fun onComplete(messageBuilder: StringBuilder) {
        if (isProcessingToolCalls && aggregator.hasCalls()) {
            val finalToolCalls = aggregator.build()
            aggregator.clear()

            ConversationService.getInstance().saveAssistantMessageWithToolCalls(
                callParameters,
                finalToolCalls
            )

            clearMessageBuilder()
            executeToolCalls(finalToolCalls)
        } else {
            CompletionProgressNotifier.Companion.update(project, false)
            wrappedListener.handleCompleted(messageBuilder.toString(), callParameters)
        }
    }

    override fun onCancelled(messageBuilder: StringBuilder) {
        toolCallHandler.cancelAllPendingApprovals()
        toolCallHandler.cancelAllExecutions()

        val statusPanelCleanup = {
            activeStatusPanels.values.forEach { statusPanel ->
                try {
                    statusPanel.complete(false, "Cancelled")
                } catch (e: Exception) {
                    logger.warn("Failed to complete status panel during cancellation: ${e.message}")
                }
            }
            activeStatusPanels.clear()
            callParameters.message.response = messageBuilder.toString()
        }

        cleanupAndComplete(statusPanelCleanup)
    }

    override fun onError(error: ErrorDetails, ex: Throwable) {
        toolCallHandler.cancelAllPendingApprovals()

        val errorCleanup = {
            activeStatusPanels.values.forEach { statusPanel ->
                try {
                    statusPanel.complete(false, "Error occurred")
                } catch (e: Exception) {
                    logger.warn("Failed to complete status panel during error: ${e.message}")
                }
            }
            activeStatusPanels.clear()

            if (messageBuilder.isNotEmpty()) {
                callParameters.message.response = messageBuilder.toString()
            }
        }

        cleanupAndHandleError(error, ex, errorCleanup)
    }

    private fun executeToolCalls(toolCalls: List<ToolCall>) {
        if (toolCalls.isEmpty()) {
            return
        }

        isProcessingToolCalls = true

        val mcpToolCalls = toolCalls
            .filterNot { it.function.name.isNullOrEmpty() }
            .map { toolCall -> McpToolConverter.toMcpToolCall(toolCall) }

        if (mcpToolCalls.isEmpty()) {
            return
        }

        executeToolCallsWithApproval(mcpToolCalls, toolCalls)
    }

    private data class ToolCallAggregate(
        var id: String?,
        var index: Int,
        var name: String,
        var type: String,
        val arguments: StringBuilder
    )

    private class ToolCallAggregator {

        private val byIndex = mutableMapOf<Int, ToolCallAggregate>()

        fun add(toolCall: ToolCall) {
            val idx = toolCall.index ?: return
            val existing = byIndex[idx]
            if (existing == null) {
                byIndex[idx] = ToolCallAggregate(
                    id = toolCall.id,
                    index = idx,
                    name = toolCall.function.name ?: "",
                    type = toolCall.type ?: "function",
                    arguments = StringBuilder(toolCall.function.arguments ?: "")
                )
            } else {
                if (!toolCall.id.isNullOrBlank() && existing.id.isNullOrBlank()) existing.id =
                    toolCall.id
                if (!toolCall.function.name.isNullOrBlank()) existing.name = toolCall.function.name
                if (!toolCall.type.isNullOrBlank()) existing.type = toolCall.type
                if (!toolCall.function.arguments.isNullOrEmpty()) existing.arguments.append(toolCall.function.arguments)
            }
        }

        fun hasCalls(): Boolean = byIndex.isNotEmpty()
        fun build(): List<ToolCall> = byIndex.values
            .distinctBy { it.index }
            .sortedBy { it.index }
            .map { agg ->
                val argsString = agg.arguments.toString()
                ToolCall(
                    agg.index,
                    agg.id ?: "tool-call-${agg.index}",
                    agg.type,
                    ToolFunctionResponse(agg.name, argsString)
                )
            }

        fun clear() = byIndex.clear()
    }

    private fun executeToolCallsWithApproval(
        mcpToolCalls: List<McpToolCall>,
        originalCalls: List<ToolCall>
    ) {
        val toolCallResults = mutableListOf<Pair<ToolCall, CompletableFuture<String>>>()
        val toolCallsWithServer = mcpToolCalls.mapNotNull { mcpToolCall ->
            val serverName = findServerForTool(mcpToolCall.name)
            if (serverName != null) {
                val toolCall = ToolCall(
                    null,
                    mcpToolCall.id,
                    "function",
                    ToolFunctionResponse(
                        mcpToolCall.name,
                        objectMapper.writeValueAsString(mcpToolCall.arguments)
                    )
                )
                Triple(mcpToolCall, toolCall, serverName)
            } else {
                logger.warn("No server found for tool: ${mcpToolCall.name}")
                null
            }
        }

        if (toolCallsWithServer.isEmpty()) {
            logger.error("No valid tool calls to execute")
            isProcessingToolCalls = false

            onComplete(messageBuilder)
            return
        }

        when (callParameters.toolApprovalMode) {
            ToolApprovalMode.AUTO_APPROVE -> {
                toolCallsWithServer.forEach { (mcpToolCall, openAIToolCall, serverName) ->
                    showExecutionStatus(mcpToolCall, serverName)

                    val future = toolCallHandler.executeToolCall(
                        toolCall = mcpToolCall,
                        serverId = serverName,
                        conversationId = callParameters.conversation.id,
                        approvalMode = ToolApprovalMode.AUTO_APPROVE,
                        onUIUpdate = { panel -> invokeLater { onToolCallUIUpdate(panel) } }
                    )
                    toolCallResults.add(openAIToolCall to future)
                }
            }

            ToolApprovalMode.REQUIRE_APPROVAL -> {
                if (toolCallsWithServer.size > 1) {
                    val expectedCount = toolCallsWithServer.size
                    fun tryScheduleContinuation() {
                        if (toolCallResults.size < expectedCount) return
                        awaitAllAndContinue(toolCallResults, originalCalls)
                    }

                    val groupPanel = ToolGroupPanel(
                        toolCalls = toolCallsWithServer.map { (_, toolCall, serverName) ->
                            val actualServerName = getActualServerName(serverName)
                            toolCall to actualServerName
                        },
                        onApprove = { toolCall, panel, _ ->
                            val match = toolCallsWithServer.find { it.second.id == toolCall.id }
                            match?.let { (mcpToolCall, _, serverName) ->
                                panel.showExecutionStatus()

                                val future = toolCallHandler.executeToolCall(
                                    toolCall = mcpToolCall,
                                    serverId = serverName,
                                    conversationId = callParameters.conversation.id,
                                    approvalMode = ToolApprovalMode.AUTO_APPROVE,
                                    onUIUpdate = { panel -> invokeLater { onToolCallUIUpdate(panel) } }
                                )
                                toolCallResults.add(toolCall to future)
                                future.whenComplete { result, throwable ->
                                    invokeLater {
                                        panel.showCompletionResult(
                                            throwable?.let { "Error: ${it.message}" } ?: result,
                                            throwable == null
                                        )
                                    }
                                }

                                tryScheduleContinuation()
                            }
                        },
                        onReject = { toolCall, panel ->
                            cleanupAndComplete()
                            toolCallHandler.cancelAllPendingApprovals()
                            toolCallHandler.cancelAllExecutions()
                        },
                        onBatchApprove = { panels, _ ->
                            toolCallsWithServer.forEachIndexed { index, (mcpToolCall, openAIToolCall, serverName) ->
                                panels[index].showExecutionStatus()

                                val future = toolCallHandler.executeToolCall(
                                    toolCall = mcpToolCall,
                                    serverId = serverName,
                                    conversationId = callParameters.conversation.id,
                                    approvalMode = ToolApprovalMode.AUTO_APPROVE,
                                    onUIUpdate = { panel -> invokeLater { onToolCallUIUpdate(panel) } }
                                )
                                toolCallResults.add(openAIToolCall to future)
                                future.whenComplete { result, throwable ->
                                    invokeLater {
                                        panels[index].showCompletionResult(
                                            throwable?.let { "Error: ${it.message}" } ?: result,
                                            throwable == null
                                        )
                                    }
                                }
                            }
                            tryScheduleContinuation()
                        },
                        onBatchReject = { panels ->
                            cleanupAndComplete()
                            toolCallHandler.cancelAllPendingApprovals()
                            toolCallHandler.cancelAllExecutions()
                        }
                    )

                    invokeLater {
                        onToolCallUIUpdate(groupPanel)
                    }
                    return
                } else {
                    toolCallsWithServer.forEach { (mcpToolCall, openAIToolCall, serverName) ->
                        val future = toolCallHandler.executeToolCall(
                            toolCall = mcpToolCall,
                            serverId = serverName,
                            conversationId = callParameters.conversation.id,
                            approvalMode = callParameters.toolApprovalMode,
                            onUIUpdate = { panel -> invokeLater { onToolCallUIUpdate(panel) } }
                        )
                        toolCallResults.add(openAIToolCall to future)
                        future.whenComplete { result, throwable ->
                            invokeLater {
                                val results = listOf(
                                    openAIToolCall to (throwable?.let { "Error: ${it.message}" }
                                        ?: result)
                                )
                                continueWithToolResults(results, originalCalls)
                            }
                        }
                    }
                    return
                }
            }

            ToolApprovalMode.BLOCK_ALL -> {
                cleanupAndComplete()
                return
            }
        }

        awaitAllAndContinue(toolCallResults, originalCalls)
    }

    private fun awaitAllAndContinue(
        toolCallResults: List<Pair<ToolCall, CompletableFuture<String>>>,
        originalCalls: List<ToolCall>
    ) {
        val allFutures = toolCallResults.map { it.second }.toTypedArray()
        CompletableFuture.allOf(*allFutures).thenRun {
            invokeLater {
                val results = toolCallResults.map { (toolCall, future) ->
                    try {
                        val result = future.join()
                        toolCall to result
                    } catch (e: Exception) {
                        logger.error("Tool '${toolCall.function.name}' failed", e)
                        toolCall to "Error: ${e.message}"
                    }
                }
                continueWithToolResults(results, originalCalls)
            }
        }.exceptionally { throwable ->
            logger.error("Tool execution failed", throwable)
            invokeLater {
                isProcessingToolCalls = false
                aggregator.clear()
                clearMessageBuilder()
                onToolCallUIUpdate.invoke(createErrorPanel("Tool execution failed: ${throwable.message}"))
                wrappedListener.handleCompleted(messageBuilder.toString(), callParameters)
            }
            null
        }
    }

    private fun findServerForTool(toolName: String): String? {
        val mcpTools = callParameters.mcpTools ?: return null
        val tool = mcpTools.find { it.name == toolName } ?: return null

        val conversationId = callParameters.conversation.id
        val attachments = sessionManager.getSessionAttachments(conversationId)
        val hasAttachment = attachments.any { it.serverId == tool.serverId }

        if (!hasAttachment) {
            logger.warn("Tool found but server not attached. Server ID: ${tool.serverId}")
            try {
                val attachFuture =
                    sessionManager.attachServerToSession(conversationId, tool.serverId)
                attachFuture.get()
            } catch (e: Exception) {
                logger.error("Failed to attach server: ${e.message}")
            }
        }

        return tool.serverId
    }

    private fun continueWithToolResults(
        toolResults: List<Pair<ToolCall, String>>,
        originalToolCalls: List<ToolCall>
    ) {
        toolResults.forEach { (toolCall, result) ->
            val isError = result.startsWith("Error:") || result.startsWith("Tool execution failed:")

            activeStatusPanels[toolCall.id]?.let { statusPanel ->
                if (isError) {
                    val msg = result.trim()
                    val formatted = if (msg.length > 100) msg.take(100) + "..." else msg
                    statusPanel.complete(false, formatted)
                } else {
                    statusPanel.complete(true, "Completed successfully")
                }
            } ?: run {
                logger.warn("No status panel found for tool ${toolCall.id} (${toolCall.function.name})")
            }
        }

        toolResults.forEach { (toolCall, _) ->
            activeStatusPanels.remove(toolCall.id)
        }

        val toolCallResultPairs = mutableListOf<Pair<ToolCall, String>>()
        val toolCallsForMatching: List<ToolCall>? = originalToolCalls

        if (toolCallsForMatching.isNullOrEmpty()) {
            logger.error("No tool calls found! Cannot continue with tool results without original tool calls")
            return
        }

        toolResults.forEach { (toolCall, result) ->
            val originalToolCall = toolCallsForMatching.find { it.id == toolCall.id }
            if (originalToolCall != null) {
                toolCallResultPairs.add(originalToolCall to result)
            } else {
                logger.warn("Could not find original tool call for tool ID: ${toolCall.id}, name: ${toolCall.function.name}")
            }
        }

        val allToolsRejected = toolResults.all { (_, result) ->
            result.contains("rejected by user") || result.contains("blocked by policy")
        }

        if (allToolsRejected) {
            cleanupAndComplete()
            return
        }

        try {
            val toolExecutionResults = mutableMapOf<String, String>()
            toolResults.forEach { (originalToolCall, result) ->
                toolExecutionResults[originalToolCall.id] = result
            }
            ConversationService.getInstance().saveToolExecutionResults(
                callParameters.conversation,
                callParameters.message,
                toolExecutionResults
            )
            logger.info("Saved tool execution outputs for ${toolResults.size} tool calls")
        } catch (e: Exception) {
            logger.error("Failed to save tool outputs in message", e)
        }

        val runner = McpContinuationRunner(
            callParameters,
            wrappedListener,
            requestHandler,
            { value -> isProcessingToolCalls = value },
            {
                isProcessingToolCalls = false
            },
            onToolCallUIUpdate
        )
        runner.run(toolCallResultPairs)
    }

    private fun createErrorPanel(errorMessage: String): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.RED, 1),
            JBUI.Borders.empty(8)
        )
        panel.background = UIUtil.getPanelBackground()

        val iconLabel = JLabel(AllIcons.General.Error)
        iconLabel.border = JBUI.Borders.emptyRight(8)
        panel.add(iconLabel)

        val messageLabel = JLabel(errorMessage)
        messageLabel.foreground = JBColor.RED
        panel.add(messageLabel)

        return panel
    }

    private fun getActualServerName(serverId: String): String {
        return try {
            val attachments = sessionManager.getSessionAttachments(callParameters.conversation.id)
            val attachment = attachments.find { it.serverId == serverId }
            attachment?.serverName ?: serverId
        } catch (_: Exception) {
            serverId
        }
    }

    private fun clearMessageBuilder() {
        if (messageBuilder.isNotEmpty()) {
            messageBuilder.clear()
        }
    }

    private fun cleanupAndComplete() {
        isProcessingToolCalls = false
        aggregator.clear()
        clearMessageBuilder()
        CompletionProgressNotifier.update(project, false)
        wrappedListener.handleCompleted(messageBuilder.toString(), callParameters)
    }

    private fun cleanupAndComplete(additionalCleanup: () -> Unit) {
        isProcessingToolCalls = false
        aggregator.clear()
        clearMessageBuilder()
        additionalCleanup()
        CompletionProgressNotifier.update(project, false)
        wrappedListener.handleCompleted(messageBuilder.toString(), callParameters)
    }

    private fun cleanupAndHandleError(error: ErrorDetails, ex: Throwable, additionalCleanup: () -> Unit = {}) {
        isProcessingToolCalls = false
        aggregator.clear()
        clearMessageBuilder()
        additionalCleanup()
        wrappedListener.handleError(error, ex)
    }

    private fun showExecutionStatus(toolCall: McpToolCall, serverId: String) {
        invokeLater {
            val serverName = getActualServerName(serverId)
            val statusPanel = McpStatusPanel(
                toolName = toolCall.name,
                serverName = serverName,
                initialStatus = "Executing...",
                args = toolCall.arguments,
            )
            activeStatusPanels[toolCall.id] = statusPanel
            onToolCallUIUpdate(statusPanel)
        }
    }
}
