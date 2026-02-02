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
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.agents.ext.tool.shell.ShellCommandConfirmation
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.tokenizer.feature.MessageTokenizer
import ai.koog.agents.features.tokenizer.feature.tokenizer
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.tokenizer.Tokenizer
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.agent.clients.*
import ee.carlrobert.codegpt.agent.credits.extractCreditsSnapshot
import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.hooks.HookManager
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.toolwindow.agent.AgentCreditsEvent
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

object AgentFactory {

    fun createAgent(
        agentType: AgentType,
        provider: ServiceType,
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
        return when (agentType) {
            AgentType.GENERAL_PURPOSE -> createGeneralPurposeAgent(
                project,
                sessionId,
                approveToolCall,
                installHandler,
                extraBehavior,
                toolOverrides,
                executor,
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
                tokenCounter,
                hookManager
            )
        }
    }

    fun createManualAgent(
        provider: ServiceType,
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

                        ${getEnvironmentInfo(project)}${behaviorSection(behavior)}
                        """.trimIndent()
                    )
                },
                model = service<ModelSelectionService>().getAgentModel(),
                maxAgentIterations = 100
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

    fun createExecutor(provider: ServiceType, events: AgentEvents? = null): PromptExecutor {
        val httpClient = HttpClientProvider.createHttpClient()
        return when (provider) {
            ServiceType.OPENAI -> {
                val apiKey = getCredential(CredentialKey.OpenaiApiKey) ?: ""
                createRetryingExecutor(OpenAILLMClient(apiKey, baseClient = httpClient), events)
            }

            ServiceType.ANTHROPIC -> {
                val apiKey = getCredential(CredentialKey.AnthropicApiKey) ?: ""
                createRetryingExecutor(AnthropicLLMClient(apiKey, baseClient = httpClient), events)
            }

            ServiceType.GOOGLE -> {
                val apiKey = getCredential(CredentialKey.GoogleApiKey) ?: ""
                createRetryingExecutor(GoogleLLMClient(apiKey, baseClient = httpClient), events)
            }

            ServiceType.OLLAMA -> {
                createRetryingExecutor(
                    OllamaClient(
                        baseUrl = service<OllamaSettings>().state.host ?: "http://localhost:11434",
                        baseClient = httpClient
                    ),
                    events
                )
            }

            ServiceType.MISTRAL -> {
                val apiKey = getCredential(CredentialKey.MistralApiKey) ?: ""
                createRetryingExecutor(MistralAILLMClient(apiKey, baseClient = httpClient), events)
            }

            ServiceType.CUSTOM_OPENAI -> {
                val state = service<CustomServicesSettings>()
                    .customServiceStateForFeatureType(FeatureType.AGENT)
                val serviceId = state.id
                    ?: throw IllegalArgumentException("No custom service configured")
                val apiKey = getCredential(CredentialKey.CustomServiceApiKeyById(serviceId))
                    ?: throw IllegalArgumentException("No API key found for custom service: $serviceId")

                createRetryingExecutor(
                    CustomOpenAILLMClient.fromSettingsState(
                        apiKey,
                        state.chatCompletionSettings,
                        httpClient
                    ),
                    events
                )
            }

            ServiceType.PROXYAI -> {
                val apiKey = getCredential(CredentialKey.CodeGptApiKey) ?: ""
                createRetryingExecutor(ProxyAILLMClient(apiKey, baseClient = httpClient), events)
            }

            ServiceType.INCEPTION -> {
                val apiKey = getCredential(CredentialKey.InceptionApiKey) ?: ""
                createRetryingExecutor(
                    InceptionAILLMClient(apiKey, baseClient = httpClient),
                    events
                )
            }

            else -> throw UnsupportedOperationException("Provider not supported: $provider")
        }
    }

    private fun createRetryingExecutor(client: LLMClient, events: AgentEvents?): PromptExecutor {
        val policy = RetryingPromptExecutor.RetryPolicy(
            maxAttempts = 5,
            initialDelay = 1.seconds,
            maxDelay = 30.seconds,
            backoffMultiplier = 2.0,
            jitterFactor = 0.1
        )
        return RetryingPromptExecutor.fromClient(client, policy, events)
    }

    private fun createGeneralPurposeAgent(
        project: Project,
        sessionId: String,
        approveToolCall: (suspend (name: String, details: String) -> Boolean)? = null,
        installFeatures: GraphAIAgent.FeatureContext.() -> Unit = {},
        extraBehavior: String? = null,
        toolOverrides: Set<SubagentTool>? = null,
        executor: PromptExecutor,
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

                        ${getEnvironmentInfo(project)}${behaviorSection(extraBehavior)}

                        # Tone and Style
                        - Keep responses short, task-focused, and free of fluff.
                        - Use GitHub-flavored Markdown for structure when helpful.

                        # Tool Usage Policy
                        - You may call multiple tools in a single turn. If calls are independent, run them in parallel. If calls depend on earlier results, run sequentially.
                        - Prefer specialized tools over bash: use Read for file content, IntelliJSearch for search, Edit/Write for code changes. Use Bash only for true shell operations.
                        - Never use Bash to "echo" thoughts or communicate. All communication happens in your response text.
                        - Never guess parameters. If a required argument is unknown, first gather it via an appropriate tool.
                        - Respect approval gates: Edit/Write operations require confirmation hooks and may be denied.
                        - Use WebSearch for library/framework questions; use ResolveLibraryId/GetLibraryDocs for dependency docs.
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
                model = service<ModelSelectionService>().getAgentModel(),
                maxAgentIterations = 100
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

                        ${getEnvironmentInfo(project)}${behaviorSection(extraBehavior)}

                        # Tool Usage Policy (Read-only)
                        - Use Read to examine files; IntelliJSearch to search patterns; WebSearch for external context; ResolveLibraryId/GetLibraryDocs for dependencies; TodoWrite to record findings.
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
                model = service<ModelSelectionService>().getAgentModel(),
                maxAgentIterations = 100
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
        val appendableResponses = appendableResponses(responses)
        appendableResponses.forEach(appendResponse)
        tokenCounter?.addAndGet(countResponseTokens(appendableResponses))
        return appendableResponses
    }

    private fun appendableResponses(responses: List<Message.Response>): List<Message.Response> {
        return responses
            .sortedBy { if (it is Message.Assistant) 0 else 1 }
            .filter { it !is Message.Reasoning }
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
        return ToolRegistry.Companion {
            if (SubagentTool.READ in selected) tool(ReadTool(project, hookManager, sessionId))
            if (SubagentTool.EDIT in selected) {
                tool(
                    ConfirmingEditTool(EditTool(project, hookManager, sessionId)) { name, details ->
                        approveToolCall?.invoke(name, details) ?: false
                    }
                )
            }
            if (SubagentTool.WRITE in selected) {
                tool(
                    ConfirmingWriteTool(WriteTool(project, hookManager)) { name, details ->
                        approveToolCall?.invoke(name, details) ?: false
                    }
                )
            }
            if (SubagentTool.TODO_WRITE in selected) tool(
                TodoWriteTool(
                    project,
                    sessionId,
                    hookManager
                )
            )
            if (SubagentTool.INTELLIJ_SEARCH in selected) tool(
                IntelliJSearchTool(
                    project = project,
                    hookManager = hookManager
                )
            )
            if (SubagentTool.WEB_SEARCH in selected) tool(
                WebSearchTool(
                    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
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
                    hookManager = hookManager,
                )
            )
            if (SubagentTool.RESOLVE_LIBRARY_ID in selected) tool(
                ResolveLibraryIdTool(
                    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
                    hookManager = hookManager,
                )
            )
            if (SubagentTool.GET_LIBRARY_DOCS in selected) tool(
                GetLibraryDocsTool(
                    workingDirectory = project.basePath ?: System.getProperty("user.dir"),
                    hookManager = hookManager
                )
            )
            if (SubagentTool.BASH in selected) {
                tool(
                    BashTool(
                        project.basePath ?: "",
                        confirmationHandler = bashConfirmationHandler,
                        sessionId = sessionId,
                        settingsService = project.service<ProxyAISettingsService>(),
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
}
