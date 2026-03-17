package ee.carlrobert.codegpt.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteMultipleTools
import ai.koog.agents.core.dsl.extension.onMultipleAssistantMessages
import ai.koog.agents.core.dsl.extension.onMultipleToolCalls
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.result
import ai.koog.agents.core.feature.handler.tool.ToolCallCompletedContext
import ai.koog.agents.core.feature.handler.tool.ToolCallStartingContext
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.agents.ext.tool.shell.ShellCommandConfirmation
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.tokenizer.feature.MessageTokenizer
import ai.koog.agents.features.tokenizer.feature.tokenizer
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.executor.clients.openai.models.ReasoningSummary
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.tokenizer.Tokenizer
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.agent.clients.CustomOpenAILLMClient
import ee.carlrobert.codegpt.agent.clients.RetryingPromptExecutor
import ee.carlrobert.codegpt.agent.credits.extractCreditsSnapshot
import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.settings.models.LLMClientFactory
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.skills.SkillDiscoveryService
import ee.carlrobert.codegpt.settings.skills.SkillPromptFormatter
import ee.carlrobert.codegpt.toolwindow.agent.AgentCreditsEvent
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

object AgentFactory {

    private const val MAX_AGENT_ITERATIONS = 250
    private const val ANTHROPIC_MIN_THINKING_BUDGET = 512
    private const val ANTHROPIC_DEFAULT_THINKING_BUDGET = 2_048

    fun createAgent(
        agentType: AgentType,
        provider: ServiceType,
        modelSelection: ModelSelection? = null,
        project: Project,
        sessionId: String,
        approveToolCall: (suspend (name: String, details: String) -> Boolean)? = null,
        onAgentToolCallStarting: ((eventContext: ToolCallStartingContext) -> Unit)? = null,
        onAgentToolCallCompleted: ((eventContext: ToolCallCompletedContext) -> Unit)? = null,
        extraBehavior: String? = null,
        toolOverrides: Set<SubagentTool>? = null,
        onCreditsAvailable: ((AgentCreditsEvent) -> Unit)? = null,
        tokenCounter: AtomicLong? = null,
        events: AgentEvents? = null,
        hookManager: HookManager
    ): AIAgent<String, String> {
        val installHandler = buildUsageAwareInstallHandler(
            provider,
            sessionId,
            onAgentToolCallStarting,
            onAgentToolCallCompleted,
            onCreditsAvailable
        )
        val executor = createExecutor(provider, events)
        val agentModel = modelSelection?.llmModel ?: service<ModelSettings>().getAgentModel()
        return when (agentType) {
            AgentType.GENERAL_PURPOSE -> createGeneralPurposeAgent(
                project,
                sessionId,
                approveToolCall,
                installHandler,
                extraBehavior,
                toolOverrides,
                executor,
                agentModel,
                tokenCounter,
                hookManager
            )

            AgentType.EXPLORE -> createExploreAgent(
                project,
                sessionId,
                approveToolCall,
                installHandler,
                extraBehavior,
                toolOverrides,
                executor,
                agentModel,
                tokenCounter,
                hookManager
            )
        }
    }

    fun createManualAgent(
        provider: ServiceType,
        modelSelection: ModelSelection? = null,
        project: Project,
        sessionId: String,
        title: String,
        behavior: String,
        toolNames: Set<String>,
        approveToolCall: (suspend (name: String, details: String) -> Boolean)? = null,
        onAgentToolCallStarting: ((eventContext: ToolCallStartingContext) -> Unit)? = null,
        onAgentToolCallCompleted: ((eventContext: ToolCallCompletedContext) -> Unit)? = null,
        onCreditsAvailable: ((AgentCreditsEvent) -> Unit)? = null,
        tokenCounter: AtomicLong? = null,
        hookManager: HookManager,
    ): AIAgent<String, String> {
        val installHandler = buildUsageAwareInstallHandler(
            provider,
            sessionId,
            onAgentToolCallStarting,
            onAgentToolCallCompleted,
            onCreditsAvailable
        )
        val executor = createExecutor(provider)
        val agentModel = modelSelection?.llmModel ?: service<ModelSettings>().getAgentModel()

        val selected = SubagentTool.parse(toolNames)
        val registry = createToolRegistry(
            project,
            sessionId,
            approveToolCall,
            selected,
            approvalBashHandler(approveToolCall),
            hookManager
        )

        return AIAgent.Companion(
            promptExecutor = executor,
            strategy = singleRunWithParallelAbility(executor, tokenCounter),
            agentConfig = AIAgentConfig(
                prompt = prompt("manual-agent") {
                    system(
                        """
                        You are a user-defined subagent named "$title".

                        ${getEnvironmentInfo(project)}${behaviorSection(behavior)}${
                            skillsSection(
                                project
                            )
                        }
                        """.trimIndent()
                    )
                },
                model = agentModel,
                maxAgentIterations = MAX_AGENT_ITERATIONS
            ),
            toolRegistry = registry,
            installFeatures = {
                installHandler()
                install(MessageTokenizer) {
                    tokenizer = object : Tokenizer {
                        override fun countTokens(text: String): Int =
                            EncodingManager.getInstance().countTokens(text)
                    }
                    enableCaching = false
                }
            }
        )
    }

    fun createExecutor(
        provider: ServiceType,
        events: AgentEvents? = null,
        featureType: FeatureType = FeatureType.AGENT
    ): PromptExecutor {
        val llmClient = LLMClientFactory.createClient(provider, featureType)
        val policy = RetryingPromptExecutor.RetryPolicy(
            maxAttempts = 5,
            initialDelay = 1.seconds,
            maxDelay = 30.seconds,
            backoffMultiplier = 2.0,
            jitterFactor = 0.1
        )
        return createRetryingExecutor(llmClient, policy, events)
    }

    internal fun createRetryingExecutor(
        client: LLMClient,
        policy: RetryingPromptExecutor.RetryPolicy,
        events: AgentEvents?
    ): PromptExecutor {
        val executor = RetryingPromptExecutor.fromClient(client, policy, events)
        return object : PromptExecutor {
            override fun executeStreaming(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ) = executor.executeStreaming(prompt.withReasoningParams(model), model, tools)

            override suspend fun execute(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ) = executor.execute(prompt.withReasoningParams(model), model, tools)

            override suspend fun moderate(prompt: Prompt, model: LLModel) =
                executor.moderate(prompt, model)

            override suspend fun models() = executor.models()

            override fun close() = executor.close()
        }
    }

    private fun Prompt.withReasoningParams(model: LLModel): Prompt {
        val params = when (model.provider) {
            LLMProvider.OpenAI -> params.withOpenAIReasoning()
            CustomOpenAILLMClient.CustomOpenAI -> {
                if (model.supports(LLMCapability.OpenAIEndpoint.Responses)) {
                    params.withOpenAIReasoning()
                } else {
                    params
                }
            }
            LLMProvider.Anthropic -> params.withAnthropicReasoning()
            else -> params
        }
        return withParams(params)
    }

    private fun LLMParams.withOpenAIReasoning(): LLMParams {
        val base = when (this) {
            is OpenAIResponsesParams -> this
            else -> OpenAIResponsesParams(
                temperature = temperature,
                maxTokens = maxTokens,
                numberOfChoices = numberOfChoices,
                speculation = speculation,
                schema = schema,
                toolChoice = toolChoice,
                user = user,
                additionalProperties = additionalProperties
            )
        }
        return base.copy(
            reasoning = base.reasoning ?: ReasoningConfig(
                effort = ReasoningEffort.MEDIUM,
                summary = ReasoningSummary.AUTO
            )
        )
    }

    private fun LLMParams.withAnthropicReasoning(): LLMParams {
        val base = when (this) {
            is AnthropicParams -> this
            else -> AnthropicParams(
                temperature = temperature,
                maxTokens = maxTokens,
                numberOfChoices = numberOfChoices,
                speculation = speculation,
                schema = schema,
                toolChoice = toolChoice,
                user = user,
                additionalProperties = additionalProperties
            )
        }

        if (base.thinking != null) return base

        val thinkingBudget = resolveAnthropicThinkingBudget(base.maxTokens) ?: return base
        return base.copy(thinking = AnthropicThinking.Enabled(budgetTokens = thinkingBudget))
    }

    private fun resolveAnthropicThinkingBudget(maxTokens: Int?): Int? {
        val limit = maxTokens ?: ANTHROPIC_DEFAULT_THINKING_BUDGET
        if (limit <= ANTHROPIC_MIN_THINKING_BUDGET) {
            return null
        }
        return (limit / 2)
            .coerceAtLeast(ANTHROPIC_MIN_THINKING_BUDGET)
            .coerceAtMost(ANTHROPIC_DEFAULT_THINKING_BUDGET)
    }

    private fun createGeneralPurposeAgent(
        project: Project,
        sessionId: String,
        approveToolCall: (suspend (name: String, details: String) -> Boolean)? = null,
        installFeatures: GraphAIAgent.FeatureContext.() -> Unit = {},
        extraBehavior: String? = null,
        toolOverrides: Set<SubagentTool>? = null,
        executor: PromptExecutor,
        agentModel: LLModel,
        tokenCounter: AtomicLong?,
        hookManager: HookManager,
    ): AIAgent<String, String> {
        val selectedTools = toolOverrides ?: SubagentTool.entries.toSet()
        return AIAgent.Companion(
            promptExecutor = executor,
            strategy = singleRunWithParallelAbility(executor, tokenCounter),
            agentConfig = AIAgentConfig(
                prompt = prompt("general-agent") {
                    system(
                        """
                        You are a general-purpose coding subagent operating inside a JetBrains IDE. You are invoked by a main agent to execute concrete steps. Work efficiently, call tools decisively, and keep messages concise and action-oriented.

                        ${getEnvironmentInfo(project)}${behaviorSection(extraBehavior)}${
                            skillsSection(
                                project
                            )
                        }

                        # Tone and Style
                        - Keep responses short, task-focused, and free of fluff.
                        - Use GitHub-flavored Markdown for structure when helpful.

                        # Tool Usage Policy
                        - You may call multiple tools in a single turn. If calls are independent, run them in parallel. If calls depend on earlier results, run sequentially.
                        - Prefer specialized tools over bash: use Read for file content, IntelliJSearch for search, Edit/Write for code changes. Use Bash only for true shell operations.
                        - Never use Bash to "echo" thoughts or communicate. All communication happens in your response text.
                        - Never guess parameters. If a required argument is unknown, first gather it via an appropriate tool.
                        - Respect approval gates: Edit/Write operations require confirmation hooks and may be denied.
                        - Use WebSearch to discover sources and WebFetch to extract a known URL into Markdown.
                        - Use ResolveLibraryId/GetLibraryDocs for library/framework/API documentation.
                        - Use TodoWrite to outline and track subtasks when a request spans multiple steps.

                        # Tool Routing Rules
                        - If the user asks about how to use a library, best practices, API semantics, configuration, or conventions: prefer ResolveLibraryId followed by GetLibraryDocs to gather authoritative guidance before proposing changes.
                        - Use IntelliJSearch to locate symbols and examples before editing files.

                        # Collaboration as Subagent
                        - Assume a parent agent orchestrates overall strategy. Focus on execution quality and clear, minimal output.
                        - Do not create further planning layers; execute tasks and report concrete results or blockers.

                        # Good Practices
                        - Be precise and cite evidence (paths/lines) for findings and changes.
                        - Batch independent reads/searches/web queries in parallel for speed.
                        - Validate at boundaries (user input, external APIs); trust internal code guarantees.
                        """.trimIndent()
                    )
                },
                model = agentModel,
                maxAgentIterations = MAX_AGENT_ITERATIONS
            ),
            toolRegistry = createToolRegistry(
                project,
                sessionId,
                approveToolCall,
                selectedTools,
                approvalBashHandler(approveToolCall),
                hookManager
            ),
            installFeatures = {
                installFeatures()
                install(MessageTokenizer) {
                    tokenizer = object : Tokenizer {
                        override fun countTokens(text: String): Int =
                            EncodingManager.getInstance().countTokens(text)
                    }
                    enableCaching = false
                }
            }
        )
    }

    private fun createExploreAgent(
        project: Project,
        sessionId: String,
        approveToolCall: (suspend (name: String, details: String) -> Boolean)? = null,
        installFeatures: GraphAIAgent.FeatureContext.() -> Unit = {},
        extraBehavior: String? = null,
        toolOverrides: Set<SubagentTool>? = null,
        executor: PromptExecutor,
        agentModel: LLModel,
        tokenCounter: AtomicLong?,
        hookManager: HookManager
    ): AIAgent<String, String> {
        val selectedTools = toolOverrides ?: (SubagentTool.readOnly + SubagentTool.BASH).toSet()
        return AIAgent.Companion(
            promptExecutor = executor,
            strategy = singleRunWithParallelAbility(executor, tokenCounter),
            agentConfig = AIAgentConfig(
                prompt = prompt("explore-agent") {
                    system(
                        """
                        You are an Explore subagent for codebase understanding. You are invoked by a main agent to gather context and answer questions by reading and searching the project. You do NOT modify files.

                        ${getEnvironmentInfo(project)}${behaviorSection(extraBehavior)}${
                            skillsSection(
                                project
                            )
                        }

                        # Tool Usage Policy (Read-only)
                        - Use Read to examine files; IntelliJSearch to search patterns; WebSearch for source discovery; WebFetch for direct URL extraction; ResolveLibraryId/GetLibraryDocs for dependencies; TodoWrite to record findings.
                        - You may call multiple tools in parallel when independent (e.g., read multiple files, run several searches at once).
                        - Do not use Edit or Write. Avoid destructive Bash. Use Bash only for safe, read-only operations if strictly necessary.
                        - Never guess parameters; first gather precise paths/patterns.

                        # Tool Routing Rules
                        - For library usage and best practices: ResolveLibraryId then GetLibraryDocs to retrieve relevant docs prior to summarizing advice.
                        - Use IntelliJSearch for code navigation and symbol discovery.

                        # Exploration Workflow
                        - Initial scan: read key config/entry files in parallel; map structure.
                        - Deep dive: run targeted searches and reads in parallel for related components.
                        - Summarize: synthesize findings with concrete references; use TodoWrite to capture a brief outline of insights.

                        # Good Practices
                        - Prefer breadth-first sampling before deep dives.
                        - Keep results actionable, with clear file paths and line references.
                        - Stop when the question is fully answered or sufficient context is gathered; otherwise continue exploration.
                        """.trimIndent()
                    )
                },
                model = agentModel,
                maxAgentIterations = MAX_AGENT_ITERATIONS
            ),
            toolRegistry = createToolRegistry(
                project,
                sessionId,
                approveToolCall = approveToolCall,
                selected = selectedTools,
                bashConfirmationHandler = { ShellCommandConfirmation.Approved },
                hookManager = hookManager
            ),
            installFeatures = {
                installFeatures()
                install(MessageTokenizer) {
                    tokenizer = object : Tokenizer {
                        override fun countTokens(text: String): Int =
                            EncodingManager.getInstance().countTokens(text)
                    }
                    enableCaching = false
                }
            }
        )
    }

    private fun buildUsageAwareInstallHandler(
        provider: ServiceType,
        sessionId: String,
        onAgentToolCallStarting: ((eventContext: ToolCallStartingContext) -> Unit)?,
        onAgentToolCallCompleted: ((eventContext: ToolCallCompletedContext) -> Unit)?,
        onCreditsAvailable: ((AgentCreditsEvent) -> Unit)?
    ): GraphAIAgent.FeatureContext.() -> Unit = {
        handleEvents {
            onToolCallStarting { ctx -> onAgentToolCallStarting?.invoke(ctx) }
            onToolCallCompleted { ctx -> onAgentToolCallCompleted?.invoke(ctx) }
            onNodeExecutionCompleted { ctx ->
                val responses = (ctx.output as? List<*>)?.filterIsInstance<Message.Response>()
                    ?: return@onNodeExecutionCompleted
                if (responses.isEmpty()) return@onNodeExecutionCompleted
                if (provider == ServiceType.PROXYAI) {
                    extractCredits(responses, sessionId)?.let { onCreditsAvailable?.invoke(it) }
                }
            }
        }
    }

    private fun extractCredits(
        responses: List<Message.Response>,
        sessionId: String
    ): AgentCreditsEvent? {
        val credits = extractCreditsSnapshot(responses) ?: return null
        return AgentCreditsEvent(
            sessionId = sessionId,
            remaining = credits.remaining,
            monthlyRemaining = credits.monthlyRemaining,
            consumed = credits.total
        )
    }

    private fun singleRunWithParallelAbility(
        executor: PromptExecutor,
        tokenCounter: AtomicLong?
    ) = strategy("subagent_single_run_sequential") {
        val nodeCallLLM by node<String, List<Message.Response>> { input ->
            llm.writeSession {
                appendPrompt { user(input) }
                tokenCounter?.addAndGet(tokenizer().tokenCountFor(prompt).toLong())
                val responses =
                    requestResponses(
                        executor,
                        config,
                        { appendPrompt { message(it) } },
                        tokenCounter
                    )
                responses
            }
        }
        val nodeExecuteTool by nodeExecuteMultipleTools(parallelTools = true)
        val nodeSendToolResult by node<List<ReceivedToolResult>, List<Message.Response>> { results ->
            llm.writeSession {
                appendPrompt {
                    results.forEach { tool { result(it) } }
                }
                tokenCounter?.addAndGet(tokenizer().tokenCountFor(prompt).toLong())
                val responses =
                    requestResponses(
                        executor,
                        config,
                        { appendPrompt { message(it) } },
                        tokenCounter
                    )
                responses
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onMultipleToolCalls { true })
        edge(
            nodeCallLLM forwardTo nodeFinish
                    onMultipleAssistantMessages { true }
                    transformed { it.joinToString("\n") { message -> message.content } }
        )

        edge(nodeExecuteTool forwardTo nodeSendToolResult)

        edge(nodeSendToolResult forwardTo nodeExecuteTool onMultipleToolCalls { true })

        edge(
            nodeSendToolResult forwardTo nodeFinish
                    onMultipleAssistantMessages { true }
                    transformed { it.joinToString("\n") { message -> message.content } }
        )
    }

    private suspend fun AIAgentLLMWriteSession.requestResponses(
        executor: PromptExecutor,
        config: AIAgentConfig,
        appendResponse: (Message.Response) -> Unit,
        tokenCounter: AtomicLong?
    ): List<Message.Response> {
        val preparedPrompt = config.missingToolsConversionStrategy.convertPrompt(prompt, tools)
        val responses = executor.execute(preparedPrompt, model, tools)
        val appendableResponses = appendableResponses(responses, model.provider)
        appendableResponses.forEach(appendResponse)
        tokenCounter?.addAndGet(countResponseTokens(appendableResponses))
        return appendableResponses
    }

    private fun appendableResponses(
        responses: List<Message.Response>,
        provider: LLMProvider
    ): List<Message.Response> {
        val sortedResponses = responses
            .sortedBy { if (it is Message.Assistant) 0 else 1 }
            .filterNot { response ->
                when {
                    response is Message.Assistant &&
                            response.hasOnlyTextContent() &&
                            response.content.isBlank() -> true

                    response is Message.Reasoning &&
                            provider != LLMProvider.Google -> true

                    else -> false
                }
            }

        return if (provider == LLMProvider.Google && sortedResponses.any { it is Message.Tool.Call }) {
            sortedResponses.filterNot { it is Message.Assistant }
        } else {
            sortedResponses
        }
    }

    private fun countResponseTokens(responses: List<Message.Response>): Long {
        val encoder = EncodingManager.getInstance()
        return responses.sumOf { response ->
            encoder.countTokens(response.content).toLong()
        }
    }

    private fun getEnvironmentInfo(project: Project): String {
        val osName = System.getProperty("os.name") ?: "Unknown"
        val osVersion = System.getProperty("os.version") ?: "Unknown"
        val currentDate = LocalDate.now()

        return """
            <environment>
            Working Directory: ${project.basePath}
            Platform: $osName
            OS Version: $osVersion
            Current Date: $currentDate
            </environment>
        """.trimIndent()
    }

    private fun createToolRegistry(
        project: Project,
        sessionId: String,
        approveToolCall: (suspend (name: String, details: String) -> Boolean)?,
        selected: Set<SubagentTool>,
        bashConfirmationHandler: BashCommandConfirmationHandler,
        hookManager: HookManager
    ): ToolRegistry {
        val contextService = project.service<AgentMcpContextService>()
        val mcpContext = contextService.get(sessionId)
        return ToolRegistry.Companion {
            if (SubagentTool.READ in selected) tool(ReadTool(project, sessionId, hookManager))
            if (SubagentTool.EDIT in selected) {
                tool(
                    ConfirmingEditTool(EditTool(project, sessionId, hookManager)) { name, details ->
                        approveToolCall?.invoke(name, details) ?: false
                    }
                )
            }
            if (SubagentTool.WRITE in selected) {
                tool(
                    ConfirmingWriteTool(
                        WriteTool(
                            project,
                            sessionId,
                            hookManager
                        )
                    ) { name, details ->
                        approveToolCall?.invoke(name, details) ?: false
                    }
                )
            }
            if (SubagentTool.TODO_WRITE in selected) tool(
                TodoWriteTool(
                    project = project,
                    sessionId = sessionId,
                    hookManager = hookManager
                )
            )
            if (SubagentTool.INTELLIJ_SEARCH in selected) tool(
                IntelliJSearchTool(
                    project = project,
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            if (SubagentTool.DIAGNOSTICS in selected) tool(
                DiagnosticsTool(
                    project = project,
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            if (SubagentTool.WEB_SEARCH in selected) tool(
                WebSearchTool(
                    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            if (SubagentTool.WEB_FETCH in selected) tool(
                WebFetchTool(
                    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            if (SubagentTool.BASH_OUTPUT in selected) tool(
                BashOutputTool(
                    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            if (SubagentTool.KILL_SHELL in selected) tool(
                KillShellTool(
                    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            if (SubagentTool.RESOLVE_LIBRARY_ID in selected) tool(
                ResolveLibraryIdTool(
                    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
                    sessionId = sessionId,
                    hookManager = hookManager,
                )
            )
            if (SubagentTool.GET_LIBRARY_DOCS in selected) tool(
                GetLibraryDocsTool(
                    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
                    sessionId = sessionId,
                    hookManager = hookManager
                )
            )
            if (SubagentTool.LOAD_SKILL in selected) tool(
                ConfirmingLoadSkillTool(
                    LoadSkillTool(
                        project = project,
                        sessionId = sessionId,
                        hookManager = hookManager
                    ),
                    project = project
                ) { name, details ->
                    approveToolCall?.invoke(name, details) ?: false
                }
            )
            if (SubagentTool.MCP in selected && mcpContext?.hasSelection() == true && mcpContext.conversationId != null) {
                contextService.get(sessionId)?.let { context ->
                    McpDynamicToolRegistry.createTools(context) { name, details ->
                        approveToolCall?.invoke(name, details) ?: false
                    }.forEach { tool(it) }
                }
            }
            if (SubagentTool.BASH in selected) {
                tool(
                    BashTool(
                        project,
                        confirmationHandler = bashConfirmationHandler,
                        sessionId = sessionId,
                        hookManager = hookManager
                    )
                )
            }
            tool(ExitTool)
        }
    }

    private fun approvalBashHandler(
        approveToolCall: (suspend (name: String, details: String) -> Boolean)?
    ): BashCommandConfirmationHandler {
        return BashCommandConfirmationHandler { args ->
            try {
                val ok = approveToolCall?.invoke("Bash", args.command) != false
                if (ok) ShellCommandConfirmation.Approved
                else ShellCommandConfirmation.Denied("User rejected the command")
            } catch (_: Exception) {
                ShellCommandConfirmation.Approved
            }
        }
    }

    private fun behaviorSection(behavior: String?): String {
        val content = behavior?.trim().orEmpty()
        return if (content.isEmpty()) "" else "\n# Subagent Behavior\n$content"
    }

    private fun skillsSection(project: Project): String {
        val skills = runCatching {
            project.service<SkillDiscoveryService>()
                .listSkills()
        }.getOrDefault(emptyList())
        if (skills.isEmpty()) return ""
        return "\n" + SkillPromptFormatter.formatForSystemPrompt(skills)
    }
}
