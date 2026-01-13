package ee.carlrobert.codegpt.agent.strategy

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.AIAgentEdgeBuilderIntermediate
import ai.koog.agents.core.dsl.builder.EdgeTransformationDslMarker
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.result
import ai.koog.agents.features.tokenizer.feature.tokenizer
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponses
import ai.koog.prompt.tokenizer.PromptTokenizer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.agent.AgentEvents
import ee.carlrobert.codegpt.agent.MessageWithContext
import ee.carlrobert.codegpt.agent.credits.extractCreditsSnapshot
import ee.carlrobert.codegpt.completions.CompletionRequestUtil
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.AgentCreditsEvent
import ee.carlrobert.codegpt.ui.textarea.TagProcessorFactory
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails
import java.util.*
import ee.carlrobert.codegpt.conversations.message.Message as ChatMessage

internal interface AgentRunStrategyProvider {
    fun build(
        project: Project,
        executor: PromptExecutor,
        projectInstructions: String?,
        previousCheckpoint: AgentCheckpointData?,
        pendingMessageQueue: ArrayDeque<MessageWithContext>,
        historyCompressionConfig: HistoryCompressionConfig,
        events: AgentEvents,
        sessionId: String,
        provider: ServiceType,
        stream: Boolean
    ): AIAgentGraphStrategy<MessageWithContext, String>
}

data class HistoryCompressionConfig(
    val isLimitExceeded: (Prompt, PromptTokenizer) -> Boolean,
    val compressionStrategy: HistoryCompressionStrategy
)

internal class SingleRunStrategyProvider : AgentRunStrategyProvider {
    override fun build(
        project: Project,
        executor: PromptExecutor,
        projectInstructions: String?,
        previousCheckpoint: AgentCheckpointData?,
        pendingMessageQueue: ArrayDeque<MessageWithContext>,
        historyCompressionConfig: HistoryCompressionConfig,
        events: AgentEvents,
        sessionId: String,
        provider: ServiceType,
        stream: Boolean
    ): AIAgentGraphStrategy<MessageWithContext, String> = strategy("single_run") {
        val nodeCallLLM by node<MessageWithContext, List<Message.Response>> { message ->
            llm.writeSession {
                if (previousCheckpoint == null) {
                    projectInstructions?.let {
                        appendPrompt {
                            user(it)
                        }
                    }
                } else {
                    val filteredMessageHistory = previousCheckpoint.messageHistory
                        .filter { it !is Message.System }
                        .toMutableList()
                        .apply {
                            if (lastOrNull() is Message.Tool.Call) {
                                removeLast()
                            }
                        }

                    for (previousMessage in filteredMessageHistory) {
                        if (previousMessage !is Message.System) {
                            appendPrompt { message(previousMessage) }
                        }
                    }
                }

                if (message.tags.isNotEmpty()) {
                    val context = buildTagContext(project, message.tags)
                    if (context.isNotBlank()) {
                        appendPrompt { user(context) }
                    }
                }

                appendPrompt { user(message.text) }

                requestAndPublish(
                    executor,
                    config,
                    tokenizer(),
                    events,
                    sessionId,
                    provider,
                    stream
                )
            }
        }

        val nodeExecuteTool by nodeExecuteMultipleTools(parallelTools = true)

        val nodeSendToolResult by node<List<ReceivedToolResult>, List<Message.Response>> { results ->
            llm.writeSession {
                appendPrompt {
                    results.forEach { tool { result(it) } }
                }

                val messages = prompt.messages
                val toolCallMessages = messages.count { it is Message.Tool.Call }
                val todoWriteToolUsed = messages.any { msg ->
                    msg is Message.Tool.Call && msg.tool == "TodoWrite"
                }

                if (toolCallMessages >= 3 && !todoWriteToolUsed) {
                    appendPrompt {
                        user("It seems that you haven't created a todo list yet. If the task on hand requires multiple steps then create a todo list to track your changes.")
                    }
                }

                if (pendingMessageQueue.isNotEmpty()) {
                    pendingMessageQueue.forEach {
                        appendPrompt { user(it.text) }
                    }
                    events.onQueuedMessagesResolved()
                    pendingMessageQueue.clear()
                }

                requestAndPublish(
                    executor,
                    config,
                    tokenizer(),
                    events,
                    sessionId,
                    provider,
                    stream
                )
            }
        }
        val nodeShowCompressionLoading by node<List<ReceivedToolResult>, List<ReceivedToolResult>> {
            events.onHistoryCompressionStateChanged(true)
            it
        }
        val nodeCompressHistory by nodeLLMCompressHistory<List<ReceivedToolResult>>(strategy = historyCompressionConfig.compressionStrategy)
        val nodeResetCompressionLoading by node<List<ReceivedToolResult>, List<ReceivedToolResult>> {
            events.onHistoryCompressionStateChanged(false)
            it
        }
        val nodeSendCompressedHistory by node<List<ReceivedToolResult>, List<Message.Response>> {
            llm.writeSession {
                requestAndPublish(
                    executor,
                    config,
                    tokenizer(),
                    events,
                    sessionId,
                    provider,
                    stream
                )
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onMultipleToolCalls { true })
        edge(nodeCallLLM forwardTo nodeFinish onSingleAssistantResponse { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult onCondition {
            llm.readSession {
                !historyCompressionConfig.isLimitExceeded(prompt, tokenizer())
            }
        })
        edge(nodeExecuteTool forwardTo nodeShowCompressionLoading onCondition {
            llm.readSession {
                historyCompressionConfig.isLimitExceeded(prompt, tokenizer())
            }
        })
        edge(nodeShowCompressionLoading forwardTo nodeCompressHistory)
        edge(nodeCompressHistory forwardTo nodeResetCompressionLoading)
        edge(nodeResetCompressionLoading forwardTo nodeSendCompressedHistory)
        edge(nodeSendToolResult forwardTo nodeFinish onEmptyOutput { true })
        edge(nodeSendToolResult forwardTo nodeFinish onSingleAssistantResponse { true })
        edge(nodeSendToolResult forwardTo nodeExecuteTool onMultipleToolCalls { true })

        edge(nodeSendCompressedHistory forwardTo nodeFinish onSingleAssistantResponse { true })
        edge(nodeSendCompressedHistory forwardTo nodeExecuteTool onMultipleToolCalls { true })
    }
}

private suspend fun AIAgentLLMWriteSession.requestResponses(
    stream: Boolean,
    executor: PromptExecutor,
    config: AIAgentConfig,
    appendResponse: (Message.Response) -> Unit
): List<Message.Response> {
    val responses = if (stream) {
        val streamFrames = mutableListOf<StreamFrame>()
        val frames = requestLLMStreaming()
        frames.collect { streamFrames.add(it) }
        streamFrames.toMessageResponses()
    } else {
        val preparedPrompt = config.missingToolsConversionStrategy.convertPrompt(prompt, tools)
        executor.execute(preparedPrompt, model, tools)
    }
    val appendableResponses = appendableResponses(responses)
    appendableResponses.forEach(appendResponse)
    return if (stream) responses else appendableResponses
}

private suspend fun AIAgentLLMWriteSession.requestAndPublish(
    executor: PromptExecutor,
    config: AIAgentConfig,
    tokenizer: PromptTokenizer,
    events: AgentEvents,
    sessionId: String,
    provider: ServiceType,
    stream: Boolean
): List<Message.Response> {
    val response = requestResponses(stream, executor, config) { appendPrompt { message(it) } }
    publishUsageAndCredits(prompt, tokenizer, events, sessionId, provider, response)
    return response
}

private fun appendableResponses(responses: List<Message.Response>): List<Message.Response> {
    return responses
        .sortedBy { if (it is Message.Assistant) 0 else 1 }
        .filter { it !is Message.Reasoning }
}

private fun publishUsageAndCredits(
    prompt: Prompt,
    tokenizer: PromptTokenizer,
    events: AgentEvents,
    sessionId: String,
    provider: ServiceType,
    responses: List<Message.Response>,
) {
    events.onTokenUsageAvailable(promptTokenCount(prompt, tokenizer).toLong())
    publishCreditsIfAvailable(sessionId, provider, responses, events)
}

private fun promptTokenCount(prompt: Prompt, tokenizer: PromptTokenizer): Int {
    return if (prompt.latestTokenUsage == 0) {
        tokenizer.tokenCountFor(prompt)
    } else {
        prompt.latestTokenUsage
    }
}

private fun buildTagContext(project: Project, tags: List<TagDetails>): String {
    val contextBuilder = StringBuilder()
    val tempMessage = ChatMessage("")
    tags.forEach { tagDetails ->
        val processor = TagProcessorFactory.getProcessor(project, tagDetails)
        processor.process(tempMessage, contextBuilder)
    }

    val fileContext = buildReferencedFilesContext(tempMessage.referencedFilePaths.orEmpty())
    val tagText = contextBuilder.toString().trim()
    return buildString {
        var hasContent = false
        if (tagText.isNotBlank()) {
            append(tagText)
            hasContent = true
        }
        if (fileContext.isNotBlank()) {
            if (hasContent) {
                append("\n\n")
            }
            append(fileContext)
        }
    }.trim()
}

private fun buildReferencedFilesContext(referencedFilePaths: List<String>): String {
    if (referencedFilePaths.isEmpty()) return ""
    val vfs = LocalFileSystem.getInstance()
    val referencedFiles = referencedFilePaths
        .asSequence()
        .mapNotNull { path -> vfs.findFileByPath(path) }
        .distinctBy { it.path }
        .map { ReferencedFile.from(it) }
        .toList()
    if (referencedFiles.isEmpty()) return ""
    return CompletionRequestUtil.buildContextBlock(referencedFiles, null)
}

private fun publishCreditsIfAvailable(
    sessionId: String,
    provider: ServiceType,
    responses: List<Message.Response>,
    events: AgentEvents,
) {
    if (provider != ServiceType.PROXYAI) return
    val credits = extractCreditsSnapshot(responses) ?: return
    val event = AgentCreditsEvent(
        sessionId = sessionId,
        remaining = credits.remaining,
        monthlyRemaining = credits.monthlyRemaining,
        consumed = credits.total
    )
    events.onCreditsAvailable(event)
}

@EdgeTransformationDslMarker
private infix fun <IncomingOutput, OutgoingInput> AIAgentEdgeBuilderIntermediate<IncomingOutput, List<Message.Response>, OutgoingInput>.onSingleAssistantResponse(
    block: suspend (Message.Response) -> Boolean
): AIAgentEdgeBuilderIntermediate<IncomingOutput, String, OutgoingInput> {
    return onIsInstance(List::class)
        .transformed { response ->
            response.filter { item -> item is Message.Response && item !is Message.Reasoning }
                .filterIsInstance<Message.Response>()
        }
        .onCondition { it.size == 1 && it[0] is Message.Assistant }
        .onCondition { messages -> block(messages[0]) }
        .transformed { it[0].content }
}

@EdgeTransformationDslMarker
private infix fun <IncomingOutput, OutgoingInput> AIAgentEdgeBuilderIntermediate<IncomingOutput, List<Message.Response>, OutgoingInput>.onEmptyOutput(
    block: suspend (Message.Response) -> Boolean
): AIAgentEdgeBuilderIntermediate<IncomingOutput, String, OutgoingInput> {
    return onIsInstance(List::class)
        .transformed { response ->
            response.filter { item -> item is Message.Response && item !is Message.Reasoning }
                .filterIsInstance<Message.Response>()
        }
        .onCondition { it.isEmpty() }
        .onCondition { messages -> block(messages[0]) }
        .transformed { it[0].content }
}
