package ee.carlrobert.codegpt.agent.external

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.model.*
import com.agentclientprotocol.transport.StdioTransport
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.AgentEvents
import ee.carlrobert.codegpt.agent.MessageWithContext
import ee.carlrobert.codegpt.agent.agentJson
import ee.carlrobert.codegpt.agent.external.acpcompat.AcpProtocol
import ee.carlrobert.codegpt.agent.external.acpcompat.JsonRpcException
import ee.carlrobert.codegpt.agent.external.acpcompat.ProtocolOptions
import ee.carlrobert.codegpt.agent.external.acpcompat.invoke
import ee.carlrobert.codegpt.agent.external.acpcompat.setNotificationHandler
import ee.carlrobert.codegpt.agent.external.acpcompat.vendor.AcpCompatibilityRegistry
import ee.carlrobert.codegpt.agent.external.acpcompat.vendor.AcpPeerProfile
import ee.carlrobert.codegpt.agent.external.host.AcpFileHost
import ee.carlrobert.codegpt.agent.external.host.AcpHostCapabilities
import ee.carlrobert.codegpt.agent.external.host.AcpTerminalHost
import ee.carlrobert.codegpt.agent.external.host.DefaultAcpTerminalProcessLauncher
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.mcp.McpSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.AcpConfigOption
import ee.carlrobert.codegpt.toolwindow.agent.AcpConfigOptions
import ee.carlrobert.codegpt.toolwindow.agent.AcpConfigOptionChoice
import ee.carlrobert.codegpt.toolwindow.agent.AgentSession
import ee.carlrobert.codegpt.toolwindow.agent.AgentToolWindowContentManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import ee.carlrobert.codegpt.util.CommandRuntimeHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class ExternalAcpAgentService(private val project: Project) {

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val FULL_ACCESS_MODE_ID = "full-access"

        val NO_OP_EVENTS = object : AgentEvents {
            override fun onQueuedMessagesResolved(message: MessageWithContext?) = Unit
            override fun onAgentException(provider: ServiceType, throwable: Throwable) = Unit
        }
    }

    private val logger = thisLogger()
    private val toolCallDecoder = AcpToolCallDecoder(agentJson)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionRoot: Path =
        Paths.get(project.basePath ?: System.getProperty("user.dir")).toAbsolutePath().normalize()
    private val states = ConcurrentHashMap<String, AcpProcessState>()
    private val sessionSetupMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun runPromptLoop(
        session: AgentSession,
        firstMessage: MessageWithContext,
        events: AgentEvents,
        pollNextQueued: () -> MessageWithContext?
    ) {
        val preset = ExternalAcpAgents.find(session.externalAgentId)
            ?: error("Unsupported external agent: ${session.externalAgentId}")
        val state = ensureSessionReady(session, preset, events)
        val debugModeEnabled = ConfigurationSettings.getState().debugModeEnabled
        if (debugModeEnabled) {
            logger.info(
                "[${preset.displayName}] run/start session=${session.sessionId} externalSessionId=${session.externalAgentSessionId} firstMessageId=${firstMessage.id}"
            )
        }

        var current: MessageWithContext? = firstMessage
        while (current != null && scope.isActive) {
            val promptMessage = current
            val externalSessionId = session.externalAgentSessionId
                ?: error("Missing ACP session id for ${session.sessionId}")

            try {
                if (debugModeEnabled) {
                    logger.info(
                        "[${preset.displayName}] prompt/send session=${session.sessionId} externalSessionId=$externalSessionId messageId=${promptMessage.id} preview=${promptMessage.text.logPreview()}"
                    )
                }
                logger.debug(
                    "Sending ACP prompt for session=${session.sessionId} externalSessionId=$externalSessionId messageId=${promptMessage.id} uiVisible=${promptMessage.uiVisible} preview=${promptMessage.text.logPreview()}"
                )
                sendPrompt(state, externalSessionId, promptMessage)
                if (debugModeEnabled) {
                    logger.info(
                        "[${preset.displayName}] prompt/sent session=${session.sessionId} externalSessionId=$externalSessionId messageId=${promptMessage.id}"
                    )
                }
                logger.debug(
                    "ACP prompt completed for session=${session.sessionId} externalSessionId=$externalSessionId messageId=${promptMessage.id}"
                )
            } catch (cancelled: CancellationException) {
                cancelSession(state, externalSessionId)
                throw cancelled
            }

            val nextMessage = pollNextQueued()
            if (nextMessage == null) {
                if (debugModeEnabled) {
                    logger.info("[${preset.displayName}] run/complete session=${session.sessionId}")
                }
                logger.debug("ACP run finished with no queued follow-up for session=${session.sessionId}")
                events.onAgentCompleted(preset.displayName)
                return
            }

            logger.debug(
                "Promoting queued ACP message for session=${session.sessionId} nextMessageId=${nextMessage.id} uiVisible=${nextMessage.uiVisible} preview=${nextMessage.text.logPreview()}"
            )
            events.onQueuedMessagesResolved(nextMessage)
            current = nextMessage
            delay(50)
        }
    }

    fun closeSession(sessionId: String) {
        states.remove(sessionId)?.let { state ->
            scope.launch {
                state.close()
            }
        }
        sessionSetupMutexes.remove(sessionId)
    }

    fun cancelSession(sessionId: String, externalSessionId: String?) {
        val state = states[sessionId] ?: return
        val activeSessionId = externalSessionId ?: return
        cancelSession(state, activeSessionId)
    }

    suspend fun warmUpSession(session: AgentSession) {
        val preset = ExternalAcpAgents.find(session.externalAgentId) ?: return
        ensureSessionReady(session, preset, NO_OP_EVENTS)
    }

    suspend fun loadConfigOptions(
        externalAgentId: String,
        existingSelections: Map<String, String> = emptyMap()
    ): List<AcpConfigOption> {
        val session = AgentSession(
            sessionId = "subagent-settings:$externalAgentId:${UUID.randomUUID()}",
            conversation = Conversation(),
            externalAgentId = externalAgentId,
            externalAgentConfigSelections = existingSelections
        )
        return try {
            warmUpSession(session)
            session.externalAgentConfigOptions
        } finally {
            closeSession(session.sessionId)
        }
    }

    suspend fun setSessionConfigOption(
        session: AgentSession,
        optionId: String,
        value: String
    ) {
        val preset = ExternalAcpAgents.find(session.externalAgentId)
            ?: error("Unsupported external agent: ${session.externalAgentId}")
        val state = ensureSessionReady(session, preset, NO_OP_EVENTS)
        val externalSessionId = session.externalAgentSessionId
            ?: error("Missing ACP session id for ${session.sessionId}")
        when (optionId) {
            AcpConfigCategories.MODE -> state.setMode(
                SessionId(externalSessionId),
                SessionModeId(value)
            )

            AcpConfigCategories.MODEL -> state.setModel(
                SessionId(externalSessionId),
                ModelId(value)
            )

            else -> {
                val option = session.externalAgentConfigOptions.firstOrNull { it.id == optionId }
                    ?: error("Unknown ACP runtime option '$optionId'")
                mergeSessionConfigOptions(
                    session,
                    state.setConfigOption(
                        SessionId(externalSessionId),
                        option,
                        value
                    )
                )
            }
        }
        if (optionId == AcpConfigCategories.MODE || optionId == AcpConfigCategories.MODEL) {
            session.externalAgentConfigOptions =
                session.externalAgentConfigOptions.updateCurrentValue(optionId, value)
        }
        session.externalAgentConfigSelections = AcpConfigOptions.normalizeSelections(
            session.externalAgentConfigOptions,
            session.externalAgentConfigSelections +
                (optionId to value) +
                session.externalAgentConfigOptions.currentSelections()
        )
    }

    private suspend fun ensureSessionReady(
        session: AgentSession,
        preset: ExternalAcpAgentPreset,
        events: AgentEvents
    ): AcpProcessState {
        val mutex = sessionSetupMutexes.computeIfAbsent(session.sessionId) { Mutex() }
        return mutex.withLock {
            val state = ensureState(session, preset, events)
            if (session.externalAgentSessionId.isNullOrBlank()) {
                session.externalAgentConfigLoading = true
                session.externalAgentSessionId = createSession(state, session)
            }
            state
        }
    }

    private suspend fun ensureState(
        session: AgentSession,
        preset: ExternalAcpAgentPreset,
        events: AgentEvents
    ): AcpProcessState {
        val existing = states[session.sessionId]
        if (existing != null && existing.preset.id == preset.id && existing.isAlive()) {
            existing.events = events
            return existing
        }

        existing?.close()
        val resolvedCommand = CommandRuntimeHelper.resolveCommand(
            command = preset.command,
            extraEnvironment = preset.env
        )
            ?: throw IllegalStateException(
                buildString {
                    append("Command '${preset.command}' not found. ")
                    when (preset.command) {
                        "npx", "node" -> {
                            append("Node.js/npm is required for this ACP runtime. ")
                            append("Ensure it is installed and available to the IDE process. ")
                            append("You can also point the runtime to an absolute executable path.")
                        }

                        else -> {
                            append("Ensure it is installed and available to the IDE process. ")
                            append("You can also point the runtime to an absolute executable path.")
                        }
                    }
                }
            )
        val enhancedEnv = CommandRuntimeHelper.createEnvironment(
            extraEnvironment = preset.env,
            resolvedCommand = resolvedCommand
        )

        val process = withContext(Dispatchers.IO) {
            GeneralCommandLine().apply {
                withExePath(resolvedCommand)
                withParameters(preset.args)
                withWorkDirectory(File(project.basePath ?: System.getProperty("user.dir")))
                withEnvironment(enhancedEnv)
                withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                withRedirectErrorStream(false)
            }.createProcess()
        }

        val state = AcpProcessState(
            proxySessionId = session.sessionId,
            preset = preset,
            launchEnv = enhancedEnv,
            process = process,
            events = events
        )
        states[session.sessionId] = state
        state.startStderrLogger()
        initialize(state)
        return state
    }

    private suspend fun initialize(state: AcpProcessState) {
        val response = state.initialize(
            ClientInfo(
                protocolVersion = PROTOCOL_VERSION,
                capabilities = state.clientCapabilities(),
                implementation = Implementation(
                    name = "ProxyAI",
                    version = "unknown",
                    title = "ProxyAI"
                )
            )
        )
        state.authMethodIds = response.authMethods.map(AuthMethod::id)
        currentSession(state.proxySessionId)?.externalAgentPeerProfileId = state.peerProfile.profileId
    }

    private suspend fun createSession(state: AcpProcessState, session: AgentSession): String {
        return try {
            val selectedMcpServerIds = selectedMcpServerIds(session.sessionId)
            val mcpServers = buildMcpServers(selectedMcpServerIds)
            val response = runCatching {
                state.createSession(
                    cwd = project.basePath ?: System.getProperty("user.dir"),
                    mcpServers = mcpServers,
                    requestMeta = session.externalAgentRequestMeta
                )
            }.recoverCatching { ex ->
                if (ex.isAuthenticationRequiredError() && state.authMethodIds.isNotEmpty()) {
                    authenticate(state, state.authMethodIds.first())
                    state.createSession(
                        cwd = project.basePath ?: System.getProperty("user.dir"),
                        mcpServers = mcpServers,
                        requestMeta = session.externalAgentRequestMeta
                    )
                } else {
                    throw ex
                }
            }.getOrThrow()
            applyRuntimeState(session, response.toRuntimeState())
            session.externalAgentMcpServerIds = selectedMcpServerIds
            session.externalAgentPeerProfileId = state.peerProfile.profileId
            val externalSessionId = response.sessionId.value
            applyConfiguredSelections(state, session, externalSessionId)
            externalSessionId
        } finally {
            session.externalAgentConfigLoading = false
        }
    }

    private suspend fun authenticate(state: AcpProcessState, methodId: AuthMethodId) {
        state.authenticate(methodId)
    }

    private fun currentSession(proxySessionId: String): AgentSession? {
        return project.service<AgentToolWindowContentManager>().getSession(proxySessionId)
    }

    private fun applyRuntimeState(
        session: AgentSession,
        runtimeState: AcpRuntimeState
    ) {
        session.externalAgentConfigOptions = buildConfigOptions(
            modes = runtimeState.modes,
            models = runtimeState.models,
            configOptions = runtimeState.configOptions
        )
        session.externalAgentConfigSelections = AcpConfigOptions.normalizeSelections(
            session.externalAgentConfigOptions,
            session.externalAgentConfigSelections + session.externalAgentConfigOptions.currentSelections()
        )
        session.externalAgentAvailableCommands = runtimeState.availableCommands
        session.externalAgentVendorMeta = runtimeState.vendorMeta
        runtimeState.sessionTitle
            ?.takeIf(String::isNotBlank)
            ?.let { session.externalAgentSessionTitle = it }
        session.externalAgentConfigLoading = false
    }

    private fun mergeSessionConfigOptions(
        session: AgentSession,
        configOptions: List<SessionConfigOption>
    ) {
        val standard = session.externalAgentConfigOptions.filter {
            it.id == AcpConfigCategories.MODE || it.id == AcpConfigCategories.MODEL
        }
        session.externalAgentConfigOptions = (standard + configOptions.toAcpConfigOptions())
            .associateByTo(linkedMapOf(), AcpConfigOption::id)
            .values
            .toList()
        session.externalAgentConfigSelections = AcpConfigOptions.normalizeSelections(
            session.externalAgentConfigOptions,
            session.externalAgentConfigSelections + session.externalAgentConfigOptions.currentSelections()
        )
    }

    private suspend fun applyConfiguredSelections(
        state: AcpProcessState,
        session: AgentSession,
        externalSessionId: String
    ) {
        val requestedSelections = session.externalAgentConfigSelections
            .mapNotNull { (optionId, value) ->
                val normalizedOptionId = optionId.trim()
                val normalizedValue = value.trim()
                if (normalizedOptionId.isBlank() || normalizedValue.isBlank()) {
                    null
                } else {
                    normalizedOptionId to normalizedValue
                }
            }
            .toMap(linkedMapOf())
        if (requestedSelections.isEmpty()) {
            return
        }

        val orderedSelections = buildList {
            val remaining = requestedSelections.toMutableMap()
            session.externalAgentConfigOptions.forEach { option ->
                remaining.remove(option.id)?.let { selectedValue ->
                    add(option.id to selectedValue)
                }
            }
            addAll(remaining.entries.map { it.key to it.value })
        }

        val sessionId = SessionId(externalSessionId)
        for ((optionId, value) in orderedSelections) {
            val option =
                session.externalAgentConfigOptions.firstOrNull { it.id == optionId } ?: continue
            if (option.currentValue == value) {
                continue
            }
            if (option.options.isNotEmpty() && option.options.none { it.value == value }) {
                logger.warn("Skipping unsupported ACP subagent option $optionId=$value for ${session.externalAgentId}")
                continue
            }

            runCatching {
                when (optionId) {
                    AcpConfigCategories.MODE -> state.setMode(sessionId, SessionModeId(value))
                    AcpConfigCategories.MODEL -> state.setModel(sessionId, ModelId(value))
                    else -> mergeSessionConfigOptions(
                        session,
                        state.setConfigOption(sessionId, option, value)
                    )
                }
                if (optionId == AcpConfigCategories.MODE || optionId == AcpConfigCategories.MODEL) {
                    session.externalAgentConfigOptions =
                        session.externalAgentConfigOptions.updateCurrentValue(optionId, value)
                }
                session.externalAgentConfigSelections = AcpConfigOptions.normalizeSelections(
                    session.externalAgentConfigOptions,
                    session.externalAgentConfigSelections +
                        (optionId to value) +
                        session.externalAgentConfigOptions.currentSelections()
                )
            }.onFailure { error ->
                logger.warn(
                    "Failed to apply ACP subagent option $optionId=$value for ${session.externalAgentId}",
                    error
                )
            }
        }
    }

    private suspend fun sendPrompt(
        state: AcpProcessState,
        externalSessionId: String,
        message: MessageWithContext
    ) {
        state.sendPrompt(
            sessionId = SessionId(externalSessionId),
            prompt = buildPromptBlocks(message)
        )
    }

    private fun cancelSession(state: AcpProcessState, externalSessionId: String) {
        runCatching {
            state.cancel(SessionId(externalSessionId))
        }.onFailure {
            logger.debug("Failed to cancel ACP session $externalSessionId", it)
        }
    }

    private fun selectedMcpServerIds(proxySessionId: String): Set<String> {
        return project.service<ee.carlrobert.codegpt.agent.AgentMcpContextService>()
            .get(proxySessionId)
            ?.selectedServerIds
            .orEmpty()
    }

    private fun buildMcpServers(selectedServerIds: Set<String>): List<McpServer> {
        if (selectedServerIds.isEmpty()) {
            return emptyList()
        }

        val serversById =
            project.service<McpSettings>().state.servers.associateBy { it.id.toString() }
        return selectedServerIds.mapNotNull { serverId ->
            val server = serversById[serverId] ?: return@mapNotNull null
            McpServer.Stdio(
                name = server.name ?: serverId,
                command = server.command ?: "npx",
                args = server.arguments,
                env = server.environmentVariables.map { (key, value) ->
                    EnvVariable(name = key, value = value)
                }
            )
        }
    }

    private fun buildPromptBlocks(message: MessageWithContext): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val selectedTags = message.tags.filter { it.selected }

        if (selectedTags.isNotEmpty()) {
            val tagSummary = buildTagSummary(selectedTags)
            if (tagSummary.isNotBlank()) {
                blocks += ContentBlock.Text(tagSummary)
            }
        }

        blocks += ContentBlock.Text(message.text)

        selectedTags.forEach { tag ->
            when (tag) {
                is FileTagDetails -> blocks += resourceLinkBlock(tag.virtualFile.path)
                is EditorTagDetails -> blocks += resourceLinkBlock(tag.virtualFile.path)
                else -> Unit
            }
        }

        return blocks
    }

    private fun resourceLinkBlock(path: String): ContentBlock.ResourceLink {
        val filePath = Paths.get(path)
        return ContentBlock.ResourceLink(
            uri = Paths.get(path).toUri().toString(),
            name = filePath.fileName?.toString() ?: path,
            mimeType = "text/plain"
        )
    }

    private fun buildTagSummary(tags: List<TagDetails>): String {
        if (tags.isEmpty()) return ""

        return buildString {
            appendLine("Additional ProxyAI context:")
            tags.forEach { tag ->
                when (tag) {
                    is FileTagDetails -> appendLine("- File: ${tag.virtualFile.path}")
                    is EditorTagDetails -> appendLine("- Open file: ${tag.virtualFile.path}")
                    is FolderTagDetails -> appendLine("- Folder: ${tag.folder.path}")
                    is SelectionTagDetails -> {
                        appendLine("- Selection from ${tag.virtualFile.path}:")
                        appendLine(tag.selectedText.orEmpty())
                    }

                    is EditorSelectionTagDetails -> {
                        appendLine("- Selection from ${tag.virtualFile.path}:")
                        appendLine(tag.selectedText.orEmpty())
                    }

                    else -> appendLine("- ${tag.name}")
                }
            }
        }.trim()
    }

    private fun buildConfigOptions(
        modes: SessionModeState?,
        models: SessionModelState?,
        configOptions: List<SessionConfigOption>
    ): List<AcpConfigOption> {
        val standard = buildList {
            models?.let { state ->
                add(
                    AcpConfigOption(
                        id = AcpConfigCategories.MODEL,
                        name = "Model",
                        category = AcpConfigCategories.MODEL,
                        type = "select",
                        currentValue = state.currentModelId.value,
                        options = state.availableModels.map { model ->
                            AcpConfigOptionChoice(
                                value = model.modelId.value,
                                name = model.name,
                                description = model.description
                            )
                        }
                    )
                )
            }
            modes?.let { state ->
                add(
                    AcpConfigOption(
                        id = AcpConfigCategories.MODE,
                        name = "Mode",
                        category = AcpConfigCategories.MODE,
                        type = "select",
                        currentValue = state.currentModeId.value,
                        options = state.availableModes.map { mode ->
                            AcpConfigOptionChoice(
                                value = mode.id.value,
                                name = mode.name,
                                description = mode.description
                            )
                        }
                    )
                )
            }
        }
        return (standard + configOptions.toAcpConfigOptions())
            .associateByTo(linkedMapOf(), AcpConfigOption::id)
            .values
            .toList()
    }

    private inner class AcpProcessState(
        val proxySessionId: String,
        val preset: ExternalAcpAgentPreset,
        val launchEnv: Map<String, String>,
        val process: Process,
        @Volatile var events: AgentEvents
    ) {
        private val compatibilityRegistry = AcpCompatibilityRegistry()

        @Volatile
        var peerProfile: AcpPeerProfile = compatibilityRegistry.initialProfile(preset)

        private val transport = StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            name = preset.displayName
        )
        private val protocol = AcpProtocol(
            scope,
            transport,
            ProtocolOptions(
                protocolDebugName = preset.displayName,
                outboundPayloadAugmenter = { methodName, payload ->
                    compatibilityRegistry.augmentOutboundPayload(
                        profile = peerProfile,
                        methodName = methodName,
                        payload = payload,
                        sessionRequestMeta = when (methodName.name) {
                            "session/new", "session/load" -> currentSession()?.externalAgentRequestMeta
                            else -> null
                        },
                        launchEnv = launchEnv
                    )
                },
                inboundPayloadNormalizer = { methodName, payload ->
                    compatibilityRegistry.normalizeInboundPayload(
                        profile = peerProfile,
                        methodName = methodName,
                        payload = payload
                    )
                },
                trace = ::acpTrace
            )
        )
        private val hostCapabilities = AcpHostCapabilities(
            fileHost = AcpFileHost(),
            terminalHost = AcpTerminalHost(DefaultAcpTerminalProcessLauncher(scope))
        )
        private val hostBridge = AcpHostBridge(
            proxySessionId = proxySessionId,
            displayName = preset.displayName,
            toolEventFlavor = preset.toolEventFlavor,
            fullAccessModeId = FULL_ACCESS_MODE_ID,
            sessionRoot = sessionRoot,
            toolCallDecoder = toolCallDecoder,
            hostCapabilities = hostCapabilities,
            currentSession = ::currentSession,
            eventsProvider = { events },
            trace = ::acpTrace
        )
        private val sessionUpdateBridge = AcpSessionUpdateBridge(
            proxySessionId = proxySessionId,
            toolEventFlavor = preset.toolEventFlavor,
            toolCallDecoder = toolCallDecoder,
            updateModeSelection = ::updateCurrentMode,
            updateConfigOptions = ::updateConfigOptions,
            updateAvailableCommands = ::updateAvailableCommands,
            updateSessionInfo = ::updateSessionInfo,
            trace = ::acpTrace
        )

        @Volatile
        var authMethodIds: List<AuthMethodId> = emptyList()

        init {
            hostBridge.register(protocol)
            protocol.setNotificationHandler(AcpMethod.ClientMethods.SessionUpdate) { notification ->
                sessionUpdateBridge.handle(notification, events)
            }
            protocol.start()
        }

        fun isAlive(): Boolean = process.isAlive

        fun clientCapabilities(): ClientCapabilities = hostCapabilities.clientCapabilities()

        fun startStderrLogger() {
            scope.launch {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            logger.info("[${preset.displayName}] $line")
                        }
                    }
                }
            }
        }

        suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
            return AcpMethod.AgentMethods.Initialize(
                protocol,
                InitializeRequest(
                    clientInfo.protocolVersion,
                    clientInfo.capabilities,
                    clientInfo.implementation,
                    clientInfo._meta
                )
            )
                .let {
                    peerProfile = compatibilityRegistry.resolveProfile(
                        preset = preset,
                        agentInfo = AgentInfo(
                            it.protocolVersion,
                            it.agentCapabilities,
                            it.authMethods,
                            it.agentInfo,
                            it._meta
                        )
                    )
                    AgentInfo(
                        it.protocolVersion,
                        it.agentCapabilities,
                        it.authMethods,
                        it.agentInfo,
                        it._meta
                    )
                }
        }

        suspend fun authenticate(methodId: AuthMethodId) {
            AcpMethod.AgentMethods.Authenticate(protocol, AuthenticateRequest(methodId))
        }

        suspend fun createSession(
            cwd: String,
            mcpServers: List<McpServer>,
            requestMeta: JsonElement? = null
        ): NewSessionResponse {
            return AcpMethod.AgentMethods.SessionNew(
                protocol,
                NewSessionRequest(cwd = cwd, mcpServers = mcpServers, _meta = requestMeta)
            )
        }

        suspend fun sendPrompt(sessionId: SessionId, prompt: List<ContentBlock>) {
            AcpMethod.AgentMethods.SessionPrompt(
                protocol,
                PromptRequest(sessionId = sessionId, prompt = prompt)
            )
        }

        fun cancel(sessionId: SessionId) {
            AcpMethod.AgentMethods.SessionCancel(protocol, CancelNotification(sessionId))
        }

        suspend fun setMode(sessionId: SessionId, modeId: SessionModeId) {
            AcpMethod.AgentMethods.SessionSetMode(
                protocol,
                SetSessionModeRequest(sessionId, modeId)
            )
        }

        suspend fun setModel(sessionId: SessionId, modelId: ModelId) {
            AcpMethod.AgentMethods.SessionSetModel(
                protocol,
                SetSessionModelRequest(sessionId, modelId)
            )
        }

        suspend fun setConfigOption(
            sessionId: SessionId,
            option: AcpConfigOption,
            value: String
        ): List<SessionConfigOption> {
            val optionValue = when (option.type) {
                "boolean" -> SessionConfigOptionValue.BoolValue(
                    value.toBooleanStrictOrNull()
                        ?: error("Invalid boolean ACP option value '$value' for ${option.id}")
                )

                else -> SessionConfigOptionValue.StringValue(value)
            }
            return AcpMethod.AgentMethods.SessionSetConfigOption(
                protocol,
                SetSessionConfigOptionRequest(
                    sessionId = sessionId,
                    configId = SessionConfigId(option.id),
                    value = optionValue
                )
            ).configOptions
        }

        private fun currentSession(): AgentSession? {
            return project.service<AgentToolWindowContentManager>().getSession(proxySessionId)
        }

        private fun updateCurrentMode(currentModeId: String) {
            currentSession()?.let { session ->
                session.externalAgentConfigOptions =
                    session.externalAgentConfigOptions.updateCurrentValue(
                        AcpConfigCategories.MODE,
                        currentModeId
                    )
                session.externalAgentConfigSelections = AcpConfigOptions.normalizeSelections(
                    session.externalAgentConfigOptions,
                    session.externalAgentConfigSelections + session.externalAgentConfigOptions.currentSelections()
                )
            }
        }

        private fun updateConfigOptions(configOptions: List<SessionConfigOption>) {
            currentSession()?.let { session ->
                mergeSessionConfigOptions(session, configOptions)
            }
        }

        private fun updateAvailableCommands(availableCommands: List<AvailableCommand>) {
            currentSession()?.externalAgentAvailableCommands = availableCommands
        }

        private fun updateSessionInfo(title: String?, updatedAt: String?) {
            currentSession()?.let { session ->
                if (!title.isNullOrBlank()) {
                    session.externalAgentSessionTitle = title
                }
            }
        }

        private fun acpTrace(message: String) {
            if (
                ConfigurationSettings.getState().debugModeEnabled ||
                preset.toolEventFlavor == AcpToolEventFlavor.GEMINI_CLI
            ) {
                logger.info("[ACP TRACE][${preset.displayName}/${peerProfile.profileId}] $message")
            }
        }

        fun close() {
            shutdownProcess()
            runCatching {
                protocol.close()
            }.onFailure { throwable ->
                logger.warn("Failed to close ACP protocol for ${preset.displayName}", throwable)
            }
        }

        private fun shutdownProcess() {
            runCatching {
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            }.onFailure { throwable ->
                logger.warn("Failed to stop ACP process for ${preset.displayName}", throwable)
            }
        }
    }

    private fun Throwable.isAuthenticationRequiredError(): Boolean {
        return (this as? JsonRpcException)?.message?.contains(
            "Authentication required",
            ignoreCase = true
        ) == true ||
                message?.contains("Authentication required", ignoreCase = true) == true
    }
}

internal object AcpConfigCategories {
    const val MODEL = "model"
    const val MODE = "mode"
    const val THOUGHT_LEVEL = "thought_level"
}

internal fun List<AcpConfigOption>.updateCurrentValue(
    optionId: String,
    value: String
): List<AcpConfigOption> {
    return map { option ->
        if (option.id == optionId) {
            option.copy(currentValue = value)
        } else {
            option
        }
    }
}

internal fun List<AcpConfigOption>.currentSelections(): Map<String, String> {
    return mapNotNull { option ->
        option.currentValue?.takeIf { it.isNotBlank() }?.let { option.id to it }
    }.toMap(linkedMapOf())
}

private fun List<SessionConfigOption>.toAcpConfigOptions(): List<AcpConfigOption> {
    return map { option ->
        when (option) {
            is SessionConfigOption.Select -> {
                val flattenedOptions = when (val selectOptions = option.options) {
                    is SessionConfigSelectOptions.Flat -> selectOptions.options.map { choice ->
                        AcpConfigOptionChoice(
                            value = choice.value.value,
                            name = choice.name,
                            description = choice.description
                        )
                    }

                    is SessionConfigSelectOptions.Grouped -> selectOptions.groups.flatMap { group ->
                        group.options.map { choice ->
                            AcpConfigOptionChoice(
                                value = choice.value.value,
                                name = "${group.name}: ${choice.name}",
                                description = choice.description
                            )
                        }
                    }
                }
                AcpConfigOption(
                    id = option.id.value,
                    name = option.name,
                    description = option.description,
                    category = option.id.value.toAcpConfigCategory(),
                    type = "select",
                    currentValue = option.currentValue.value,
                    options = flattenedOptions
                )
            }

            is SessionConfigOption.BooleanOption -> AcpConfigOption(
                id = option.id.value,
                name = option.name,
                description = option.description,
                category = option.id.value.toAcpConfigCategory(),
                type = "boolean",
                currentValue = option.currentValue.toString(),
                options = listOf(
                    AcpConfigOptionChoice("true", "Enabled"),
                    AcpConfigOptionChoice("false", "Disabled")
                )
            )
        }
    }
}

private fun String.toAcpConfigCategory(): String? {
    return when (lowercase()) {
        AcpConfigCategories.MODEL -> AcpConfigCategories.MODEL
        AcpConfigCategories.MODE -> AcpConfigCategories.MODE
        AcpConfigCategories.THOUGHT_LEVEL,
        "reasoning",
        "reasoning_effort" -> AcpConfigCategories.THOUGHT_LEVEL
        else -> null
    }
}
