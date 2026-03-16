package ee.carlrobert.codegpt.agent.external

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import ee.carlrobert.codegpt.agent.AgentEvents
import ee.carlrobert.codegpt.agent.MessageWithContext
import ee.carlrobert.codegpt.agent.ToolSpecs
import ee.carlrobert.codegpt.agent.tools.BashTool
import ee.carlrobert.codegpt.agent.tools.EditTool
import ee.carlrobert.codegpt.agent.tools.WriteTool
import ee.carlrobert.codegpt.settings.mcp.McpSettings
import ee.carlrobert.codegpt.toolwindow.agent.AgentSession
import ee.carlrobert.codegpt.toolwindow.agent.AgentToolWindowContentManager
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.*
import ee.carlrobert.codegpt.ui.textarea.header.tag.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists

private data class ExternalToolCall(
    val toolName: String,
    val args: Any?
)

@Service(Service.Level.PROJECT)
class ExternalAcpAgentService(private val project: Project) {

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val FULL_ACCESS_MODE_ID = "full-access"

        val NO_OP_EVENTS = object : AgentEvents {
            override fun onQueuedMessagesResolved() = Unit
            override fun onAgentException(
                provider: ee.carlrobert.codegpt.settings.service.ServiceType,
                throwable: Throwable
            ) = Unit
        }
    }

    private val logger = thisLogger()
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionConfigAdapter = AcpSessionConfigAdapter(json)
    private val toolCallDecoder = AcpToolCallDecoder(json)
    private val sessionUpdateParser = AcpSessionUpdateParser(toolCallDecoder)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

        var current: MessageWithContext? = firstMessage
        while (current != null && scope.isActive) {
            val promptMessage = current
            val externalSessionId = session.externalAgentSessionId
                ?: error("Missing ACP session id for ${session.sessionId}")

            try {
                sendPrompt(state, externalSessionId, promptMessage)
            } catch (cancelled: CancellationException) {
                cancelSession(state, externalSessionId)
                throw cancelled
            }

            val nextMessage = pollNextQueued()
            if (nextMessage == null) {
                events.onAgentCompleted(preset.displayName)
                return
            }

            events.onQueuedMessagesResolved()
            current = nextMessage
            delay(50)
        }
    }

    fun closeSession(sessionId: String) {
        states.remove(sessionId)?.close()
        sessionSetupMutexes.remove(sessionId)
    }

    suspend fun cancelSession(sessionId: String, externalSessionId: String?) {
        val state = states[sessionId] ?: return
        val activeSessionId = externalSessionId ?: return
        cancelSession(state, activeSessionId)
    }

    suspend fun warmUpSession(session: AgentSession) {
        val preset = ExternalAcpAgents.find(session.externalAgentId) ?: return
        ensureSessionReady(session, preset, NO_OP_EVENTS)
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
        val result = sessionConfigAdapter.updateOption(
            request = AcpConfigUpdateRequest(
                sessionId = externalSessionId,
                optionId = optionId,
                value = value
            ),
            support = state.configUpdateSupport,
            sendRequest = state::sendRequest
        )
        when (result) {
            AcpConfigUpdateResult.Unsupported -> {
                state.configUpdateSupport = AcpConfigUpdateSupport.Unsupported
                throw IllegalStateException("${preset.displayName} does not support runtime option changes")
            }

            is AcpConfigUpdateResult.Applied -> {
                state.configUpdateSupport = result.support
                updateSessionConfigOptions(session, result.response)
            }
        }
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
        val resolvedCommand = AcpProcessHelper.resolveCommand(
            command = preset.command,
            extraEnvironment = preset.env
        )
            ?: throw IllegalStateException(
                AcpProcessHelper.getCommandNotFoundMessage(preset.command)
            )
        val enhancedEnv = AcpProcessHelper.createEnvironment(
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
            process = process,
            events = events
        )
        states[session.sessionId] = state
        state.startReader()
        state.startStderrLogger()
        initialize(state)
        return state
    }

    private suspend fun initialize(state: AcpProcessState) {
        val response = state.sendRequest(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                putJsonObject("clientCapabilities") {
                    putJsonObject("fs") {
                        put("readTextFile", true)
                        put("writeTextFile", true)
                    }
                }
                putJsonObject("clientInfo") {
                    put("name", "proxyai")
                    put("title", "ProxyAI")
                    put("version", "dev")
                }
            }
        )
        state.authMethodIds =
            response["authMethods"]?.jsonArray?.mapNotNull { it.jsonObject.string("id") }.orEmpty()
    }

    private suspend fun createSession(state: AcpProcessState, session: AgentSession): String {
        return try {
            val response = runCatching {
                state.sendRequest(
                    method = "session/new",
                    params = buildJsonObject {
                        put("cwd", project.basePath ?: System.getProperty("user.dir"))
                        put("mcpServers", buildMcpServers(session.sessionId))
                    }
                )
            }.recoverCatching { ex ->
                if (ex.isAuthenticationRequiredError() && state.authMethodIds.isNotEmpty()) {
                    authenticate(state, state.authMethodIds.first())
                    state.sendRequest(
                        method = "session/new",
                        params = buildJsonObject {
                            put("cwd", project.basePath ?: System.getProperty("user.dir"))
                            put("mcpServers", buildMcpServers(session.sessionId))
                        }
                    )
                } else {
                    throw ex
                }
            }.getOrThrow()
            updateSessionConfigOptions(session, response)
            response["sessionId"]?.jsonPrimitive?.content
                ?: error("ACP agent did not return a sessionId")
        } finally {
            session.externalAgentConfigLoading = false
        }
    }

    private suspend fun authenticate(state: AcpProcessState, methodId: String) {
        state.sendRequest(
            method = "authenticate",
            params = buildJsonObject {
                put("methodId", methodId)
            }
        )
    }

    private fun updateSessionConfigOptions(session: AgentSession, response: JsonObject) {
        session.externalAgentConfigOptions = sessionConfigAdapter.merge(
            existing = session.externalAgentConfigOptions,
            response = response
        )
        session.externalAgentConfigLoading = false
    }

    private suspend fun sendPrompt(
        state: AcpProcessState,
        externalSessionId: String,
        message: MessageWithContext
    ) {
        state.sendRequest(
            method = "session/prompt",
            params = buildJsonObject {
                put("sessionId", externalSessionId)
                put("prompt", buildPromptBlocks(message))
            }
        )
    }

    private suspend fun cancelSession(state: AcpProcessState, externalSessionId: String) {
        runCatching {
            state.sendNotification(
                method = "session/cancel",
                params = buildJsonObject {
                    put("sessionId", externalSessionId)
                }
            )
        }.onFailure {
            logger.debug("Failed to cancel ACP session $externalSessionId", it)
        }
    }

    private fun buildMcpServers(proxySessionId: String): JsonArray {
        val selectedServerIds =
            project.service<ee.carlrobert.codegpt.agent.AgentMcpContextService>()
                .get(proxySessionId)
                ?.selectedServerIds
                .orEmpty()
        if (selectedServerIds.isEmpty()) {
            return JsonArray(emptyList())
        }

        val serversById =
            project.service<McpSettings>().state.servers.associateBy { it.id.toString() }
        return JsonArray(selectedServerIds.mapNotNull { serverId ->
            val server = serversById[serverId] ?: return@mapNotNull null
            buildJsonObject {
                put("type", "stdio")
                put("name", server.name ?: serverId)
                put("command", server.command ?: "npx")
                putJsonArray("args") {
                    server.arguments.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("env") {
                    server.environmentVariables.forEach { (key, value) ->
                        add(
                            buildJsonObject {
                                put("name", key)
                                put("value", value)
                            }
                        )
                    }
                }
            }
        })
    }

    private fun buildPromptBlocks(message: MessageWithContext): JsonArray {
        val blocks = mutableListOf<JsonElement>()
        val selectedTags = message.tags.filter { it.selected }

        if (selectedTags.isNotEmpty()) {
            val tagSummary = buildTagSummary(selectedTags)
            if (tagSummary.isNotBlank()) {
                blocks += buildJsonObject {
                    put("type", "text")
                    put("text", tagSummary)
                }
            }
        }

        blocks += buildJsonObject {
            put("type", "text")
            put("text", message.text)
        }

        selectedTags.forEach { tag ->
            when (tag) {
                is FileTagDetails -> blocks += resourceLinkBlock(tag.virtualFile.path)
                is EditorTagDetails -> blocks += resourceLinkBlock(tag.virtualFile.path)
                else -> Unit
            }
        }

        return JsonArray(blocks)
    }

    private fun resourceLinkBlock(path: String): JsonObject {
        val filePath = Paths.get(path)
        return buildJsonObject {
            put("type", "resource_link")
            put("uri", Paths.get(path).toUri().toString())
            put("name", filePath.fileName?.toString() ?: path)
            put("mimeType", "text/plain")
        }
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

    private inner class AcpProcessState(
        val proxySessionId: String,
        val preset: ExternalAcpAgentPreset,
        val process: Process,
        @Volatile var events: AgentEvents
    ) {
        private val toolCallsById = ConcurrentHashMap<String, ExternalToolCall>()
        private val rpcConnection = AcpJsonRpcConnection(
            json = json,
            process = process,
            scope = scope,
            logger = logger,
            processName = preset.displayName,
            onRequest = ::handleRequest,
            onNotification = { notification -> handleNotification(notification, events) }
        )

        @Volatile
        var authMethodIds: List<String> = emptyList()

        @Volatile
        var configUpdateSupport: AcpConfigUpdateSupport = AcpConfigUpdateSupport.Unknown

        fun isAlive(): Boolean = rpcConnection.isAlive()

        fun startReader() = rpcConnection.startReader()

        fun startStderrLogger() = rpcConnection.startStderrLogger()

        suspend fun sendRequest(method: String, params: JsonObject): JsonObject =
            rpcConnection.request(method, params)

        suspend fun sendNotification(method: String, params: JsonObject) =
            rpcConnection.notify(method, params)

        private suspend fun handleRequest(request: AcpJsonRpcRequest): JsonElement? {
            return when (request.method) {
                "session/request_permission", "requestPermission" ->
                    handleRequestPermission(request.params)

                "fs/read_text_file", "readTextFile" -> handleReadTextFile(request.params)
                "fs/write_text_file", "writeTextFile" -> handleWriteTextFile(request.params)
                else -> null
            }
        }

        private fun handleNotification(notification: AcpJsonRpcNotification, events: AgentEvents) {
            when (val update = sessionUpdateParser.parse(notification)) {
                null -> Unit
                is AcpSessionUpdate.TextChunk -> events.onTextReceived(update.text)
                is AcpSessionUpdate.ThoughtChunk -> events.onTextReceived("<think>${update.text}</think>")
                is AcpSessionUpdate.ToolCall -> handleToolCall(update, events)
                is AcpSessionUpdate.ToolCallUpdate -> handleToolCallUpdate(update, events)
                is AcpSessionUpdate.ConfigOptionUpdate -> handleConfigOptionUpdate(update.update)
            }
        }

        private suspend fun handleRequestPermission(params: JsonObject): JsonObject {
            val requestData = toolCallDecoder.decodePermissionRequest(params)
            val session = currentSession()
            val mode = session.currentAcpMode()
            if (mode == FULL_ACCESS_MODE_ID) {
                logger.debug(
                    "Auto-approving ${preset.displayName} ACP permission in mode=$mode for tool=${requestData.toolName} title=${requestData.rawTitle}"
                )
                return permissionResponse(selectApprovedPermissionOptionId(requestData.options))
            }

            val request = buildApprovalRequest(
                requestData.rawTitle,
                requestData.details,
                requestData.toolName,
                requestData.parsedArgs
            )
            val approved = runCatching { events.approveToolCall(request) }.getOrDefault(false)
            val selectedOptionId = if (approved) {
                selectApprovedPermissionOptionId(requestData.options)
            } else {
                selectRejectedPermissionOptionId(requestData.options)
            }
            logger.debug(
                "Resolved ${preset.displayName} ACP permission in mode=$mode for tool=${requestData.toolName} approved=$approved title=${requestData.rawTitle}"
            )
            return permissionResponse(selectedOptionId)
        }

        private fun handleToolCall(update: AcpSessionUpdate.ToolCall, events: AgentEvents) {
            val toolCall = update.toolCall
            toolCallsById[toolCall.id] = ExternalToolCall(toolCall.toolName, toolCall.args)
            events.onToolStarting(toolCall.id, toolCall.toolName, toolCall.args)

            if (update.status?.isTerminal == true) {
                completeToolCall(
                    toolCallId = toolCall.id,
                    toolName = toolCall.toolName,
                    args = toolCall.args,
                    status = update.status,
                    rawOutput = update.rawOutput,
                    events = events
                )
            }
        }

        private fun handleToolCallUpdate(
            update: AcpSessionUpdate.ToolCallUpdate,
            events: AgentEvents
        ) {
            if (!update.status.isTerminal) {
                return
            }

            val toolCall = toolCallsById[update.toolCallId]
            completeToolCall(
                toolCallId = update.toolCallId,
                toolName = toolCall?.toolName ?: "Tool",
                args = toolCall?.args,
                status = update.status,
                rawOutput = update.rawOutput,
                events = events
            )
        }

        private fun completeToolCall(
            toolCallId: String,
            toolName: String,
            args: Any?,
            status: AcpToolCallStatus,
            rawOutput: JsonElement?,
            events: AgentEvents
        ) {
            val result = toolCallDecoder.decodeResult(
                toolName = toolName,
                args = args,
                status = status,
                rawOutput = rawOutput
            )
            toolCallsById.remove(toolCallId)
            events.onToolCompleted(toolCallId, toolName, result)
        }

        private fun handleConfigOptionUpdate(update: JsonObject) {
            project.service<AgentToolWindowContentManager>()
                .getSession(proxySessionId)
                ?.let { session ->
                    updateSessionConfigOptions(session, update)
                }
        }

        private fun handleReadTextFile(params: JsonObject): JsonObject {
            val path = requestPath(params)
            val raw = Files.readString(path)
            val line = params["line"]?.jsonPrimitive?.intOrNull
            val limit = params["limit"]?.jsonPrimitive?.intOrNull
            val content = if (line != null && limit != null && line > 0 && limit > 0) {
                raw.lineSequence()
                    .drop(line - 1)
                    .take(limit)
                    .joinToString("\n")
            } else {
                raw
            }
            return buildJsonObject {
                put("content", content)
            }
        }

        private fun handleWriteTextFile(params: JsonObject): JsonElement {
            val path = requestPath(params)
            val content = params["content"]?.jsonPrimitive?.content ?: ""
            if (path.parent != null && path.parent.notExists()) {
                path.parent.createDirectories()
            }
            Files.writeString(path, content)
            val virtualFile =
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())
            if (virtualFile != null) {
                VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)
            } else {
                val parent = path.parent?.toFile()
                if (parent != null) {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent)
                        ?.let { parentVf ->
                            VfsUtil.markDirtyAndRefresh(false, false, true, parentVf)
                        }
                }
            }
            return JsonNull
        }

        private fun selectApprovedPermissionOptionId(options: JsonArray): String {
            return selectPermissionOptionId(
                options = options,
                preferredKinds = listOf("allow_once", "allow_always", "trust"),
                fallback = "allow"
            )
        }

        private fun selectRejectedPermissionOptionId(options: JsonArray): String {
            return selectPermissionOptionId(
                options = options,
                preferredKinds = listOf("reject_once", "reject_always", "deny", "abort", "cancel"),
                fallback = "abort",
                predicate = { value ->
                    value.contains("reject") || value.contains("deny") ||
                            value.contains("abort") || value.contains("cancel")
                }
            )
        }

        private fun selectPermissionOptionId(
            options: JsonArray,
            preferredKinds: List<String>,
            fallback: String,
            predicate: (String) -> Boolean = { false }
        ): String {
            preferredKinds.forEach { preferred ->
                options.firstOrNull { optionMatches(it.jsonObject, preferred) }?.let {
                    return optionIdOf(it.jsonObject) ?: preferred
                }
            }

            options.firstOrNull { option ->
                optionValues(option.jsonObject).any(predicate)
            }?.let { option ->
                return optionIdOf(option.jsonObject) ?: fallback
            }

            return options.firstOrNull()?.jsonObject?.let(::optionIdOf) ?: fallback
        }

        private fun optionMatches(option: JsonObject, expected: String): Boolean {
            return optionValues(option).any { it == expected }
        }

        private fun optionValues(option: JsonObject): List<String> {
            return listOfNotNull(
                option["kind"]?.jsonPrimitive?.contentOrNull?.lowercase(),
                option["optionId"]?.jsonPrimitive?.contentOrNull?.lowercase(),
                option["id"]?.jsonPrimitive?.contentOrNull?.lowercase()
            )
        }

        private fun optionIdOf(option: JsonObject): String? {
            return option["optionId"]?.jsonPrimitive?.contentOrNull
                ?: option["id"]?.jsonPrimitive?.contentOrNull
        }

        private fun buildApprovalRequest(
            rawTitle: String,
            details: String,
            toolName: String,
            parsedArgs: Any?
        ): ToolApprovalRequest {
            val approvalType = when (parsedArgs) {
                is WriteTool.Args -> ToolApprovalType.WRITE
                is EditTool.Args -> ToolApprovalType.EDIT
                is BashTool.Args -> ToolApprovalType.BASH
                else -> ToolSpecs.approvalTypeFor(toolName)
            }
            val payload = approvalPayload(parsedArgs)
            val title = rawTitle.ifBlank {
                when (approvalType) {
                    ToolApprovalType.BASH -> "Run shell command?"
                    ToolApprovalType.WRITE -> "Write file?"
                    ToolApprovalType.EDIT -> "Edit file?"
                    ToolApprovalType.GENERIC -> "Allow action?"
                }
            }
            return ToolApprovalRequest(
                type = approvalType,
                title = title,
                details = details,
                payload = payload
            )
        }

        private fun approvalPayload(parsedArgs: Any?): ToolApprovalPayload? {
            return when (parsedArgs) {
                is WriteTool.Args -> WritePayload(parsedArgs.filePath, parsedArgs.content)
                is EditTool.Args -> EditPayload(
                    filePath = parsedArgs.filePath,
                    oldString = parsedArgs.oldString,
                    newString = parsedArgs.newString,
                    replaceAll = parsedArgs.replaceAll
                )

                is BashTool.Args -> BashPayload(parsedArgs.command, parsedArgs.description)
                else -> null
            }
        }

        private fun permissionResponse(optionId: String): JsonObject {
            return buildJsonObject {
                putJsonObject("outcome") {
                    put("outcome", "selected")
                    put("optionId", optionId)
                }
            }
        }

        private fun currentSession(): AgentSession? {
            return project.service<AgentToolWindowContentManager>().getSession(proxySessionId)
        }

        fun close() = rpcConnection.close()
    }

    private fun AgentSession?.currentAcpMode(): String? {
        return this?.externalAgentConfigOptions
            ?.firstOrNull(AcpSessionConfigId.MODE::matches)
            ?.currentValue
    }

    private fun requestPath(params: JsonObject): Path {
        val rawPath = params["path"]?.jsonPrimitive?.contentOrNull
            ?: params["uri"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing path")
        return uriToPath(rawPath)
    }

    private fun uriToPath(uri: String): Path {
        return when {
            uri.startsWith("file://") -> Paths.get(java.net.URI.create(uri))
            else -> Paths.get(uri)
        }
    }

    private fun Throwable.isAuthenticationRequiredError(): Boolean {
        return message?.contains("Authentication required", ignoreCase = true) == true
    }
}
