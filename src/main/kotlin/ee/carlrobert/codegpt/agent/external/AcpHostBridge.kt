package ee.carlrobert.codegpt.agent.external

import com.agentclientprotocol.model.*
import ee.carlrobert.codegpt.agent.AgentEvents
import ee.carlrobert.codegpt.agent.ToolSpecs
import ee.carlrobert.codegpt.agent.external.acpcompat.AcpProtocol
import ee.carlrobert.codegpt.agent.external.acpcompat.acpFail
import ee.carlrobert.codegpt.agent.external.acpcompat.setRequestHandler
import ee.carlrobert.codegpt.agent.external.events.AcpToolCallArgs
import ee.carlrobert.codegpt.agent.external.host.AcpHostCapabilities
import ee.carlrobert.codegpt.agent.external.host.AcpHostSessionContext
import ee.carlrobert.codegpt.toolwindow.agent.AgentSession
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.nio.file.Path
import kotlin.io.path.name

internal class AcpHostBridge(
    private val proxySessionId: String,
    private val displayName: String,
    private val toolEventFlavor: AcpToolEventFlavor,
    private val fullAccessModeId: String,
    private val sessionRoot: Path,
    private val toolCallDecoder: AcpToolCallDecoder,
    private val hostCapabilities: AcpHostCapabilities,
    private val currentSession: () -> AgentSession?,
    private val eventsProvider: () -> AgentEvents,
    private val trace: (String) -> Unit
) {
    private val logger = KotlinLogging.logger {}

    fun register(protocol: AcpProtocol) {
        protocol.setRequestHandler(AcpMethod.ClientMethods.SessionRequestPermission) { request ->
            handleRequestPermission(request)
        }
        protocol.setRequestHandler(AcpMethod.ClientMethods.FsReadTextFile) { request ->
            handleReadTextFile(request)
        }
        protocol.setRequestHandler(AcpMethod.ClientMethods.FsWriteTextFile) { request ->
            handleWriteTextFile(request)
        }
        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalCreate) { request ->
            handleCreateTerminal(request)
        }
        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalOutput) { request ->
            handleTerminalOutput(request)
        }
        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalRelease) { request ->
            handleTerminalRelease(request)
        }
        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalWaitForExit) { request ->
            handleTerminalWaitForExit(request)
        }
        protocol.setRequestHandler(AcpMethod.ClientMethods.TerminalKill) { request ->
            handleTerminalKill(request)
        }
    }

    private suspend fun handleRequestPermission(
        request: RequestPermissionRequest
    ): RequestPermissionResponse {
        val permissionRequest = toolCallDecoder.decodePermissionRequest(toolEventFlavor, request)
        val toolCall = permissionRequest.toolCall
        val mode = currentSession().currentAcpMode()
        trace(
            "permission/request session=$proxySessionId mode=$mode tool=${toolCall.toolName} title=${toolCall.title.logPreview()} options=${permissionRequest.options.joinToString { it.kind.name }} rawInput=${request.toolCall.rawInput.logSummary()} details=${
                permissionRequest.details.logPreview(
                    200
                )
            }"
        )
        logger.debug {
            "Received $displayName ACP permission request for session=$proxySessionId mode=$mode tool=${toolCall.toolName} title=${toolCall.title.logPreview()} details=${permissionRequest.details.logPreview()} options=${permissionRequest.options.joinToString { it.kind.name }}"
        }
        if (mode == fullAccessModeId) {
            trace("permission/auto-approve session=$proxySessionId mode=$mode tool=${toolCall.toolName}")
            logger.debug {
                "Auto-approving $displayName ACP permission in mode=$mode for tool=${toolCall.toolName} title=${toolCall.title}"
            }
            return permissionResponse(selectApprovedPermissionOptionId(permissionRequest.options))
        }

        val approvalRequest = buildApprovalRequest(
            rawTitle = toolCall.title,
            details = permissionRequest.details,
            toolName = toolCall.toolName,
            parsedArgs = toolCall.args
        )
        logger.debug {
            "Queueing UI approval for session=$proxySessionId type=${approvalRequest.type} title=${approvalRequest.title.logPreview()} payload=${approvalRequest.payload.logSummary()}"
        }
        trace(
            "permission/await-ui session=$proxySessionId type=${approvalRequest.type} title=${approvalRequest.title.logPreview()} payload=${approvalRequest.payload.logSummary()}"
        )
        return try {
            val approved = eventsProvider().approveToolCall(approvalRequest)
            val selectedOptionId = if (approved) {
                selectApprovedPermissionOptionId(permissionRequest.options)
            } else {
                selectRejectedPermissionOptionId(permissionRequest.options)
            }
            trace(
                "permission/resolved session=$proxySessionId tool=${toolCall.toolName} approved=$approved selectedOption=${selectedOptionId.value}"
            )
            logger.debug {
                "Resolved $displayName ACP permission in mode=$mode for tool=${toolCall.toolName} approved=$approved title=${toolCall.title}"
            }
            permissionResponse(selectedOptionId)
        } catch (_: CancellationException) {
            trace("permission/cancelled session=$proxySessionId tool=${toolCall.toolName}")
            permissionCancelledResponse()
        } catch (error: Exception) {
            logger.warn(error) {
                "Permission approval failed for $displayName tool=${toolCall.toolName}; defaulting to reject"
            }
            permissionResponse(selectRejectedPermissionOptionId(permissionRequest.options))
        }
    }

    private suspend fun handleReadTextFile(params: ReadTextFileRequest): ReadTextFileResponse {
        trace("fs/read_text_file session=$proxySessionId path=${params.path} line=${params.line} limit=${params.limit}")
        return runHostCall(
            invalidRequestMessage = "Invalid read_text_file request",
            failureTrace = "fs/read_text_file/failed session=$proxySessionId path=${params.path}"
        ) {
            hostCapabilities.readTextFile(hostSessionContext(params.sessionId), params)
        }
    }

    private suspend fun handleWriteTextFile(params: WriteTextFileRequest): WriteTextFileResponse {
        trace("fs/write_text_file session=$proxySessionId path=${params.path}")
        return runHostCall(
            invalidRequestMessage = "Invalid write_text_file request",
            failureTrace = "fs/write_text_file/failed session=$proxySessionId path=${params.path}"
        ) {
            hostCapabilities.writeTextFile(hostSessionContext(params.sessionId), params)
        }
    }

    private suspend fun handleCreateTerminal(params: CreateTerminalRequest): CreateTerminalResponse {
        trace(
            "terminal/create session=$proxySessionId requestSession=${params.sessionId.value} command=${params.command.logPreview()} cwd=${params.cwd?.logPreview()}"
        )
        return runHostCall(
            invalidRequestMessage = "Invalid terminal/create request",
            failureTrace = "terminal/create/failed session=$proxySessionId command=${params.command.logPreview()}"
        ) {
            hostCapabilities.createTerminal(hostSessionContext(params.sessionId), params)
        }
    }

    private suspend fun handleTerminalOutput(params: TerminalOutputRequest): TerminalOutputResponse {
        trace(
            "terminal/output session=$proxySessionId requestSession=${params.sessionId.value} terminalId=${params.terminalId}"
        )
        return runHostCall(
            invalidRequestMessage = "Invalid terminal/output request",
            failureTrace = "terminal/output/failed session=$proxySessionId terminalId=${params.terminalId}"
        ) {
            hostCapabilities.output(hostSessionContext(params.sessionId), params)
        }
    }

    private suspend fun handleTerminalRelease(params: ReleaseTerminalRequest): ReleaseTerminalResponse {
        trace(
            "terminal/release session=$proxySessionId requestSession=${params.sessionId.value} terminalId=${params.terminalId}"
        )
        return runHostCall(
            invalidRequestMessage = "Invalid terminal/release request",
            failureTrace = "terminal/release/failed session=$proxySessionId terminalId=${params.terminalId}"
        ) {
            hostCapabilities.release(hostSessionContext(params.sessionId), params)
        }
    }

    private suspend fun handleTerminalWaitForExit(params: WaitForTerminalExitRequest): WaitForTerminalExitResponse {
        trace(
            "terminal/wait_for_exit session=$proxySessionId requestSession=${params.sessionId.value} terminalId=${params.terminalId}"
        )
        return runHostCall(
            invalidRequestMessage = "Invalid terminal/wait_for_exit request",
            failureTrace = "terminal/wait_for_exit/failed session=$proxySessionId terminalId=${params.terminalId}"
        ) {
            hostCapabilities.waitForExit(hostSessionContext(params.sessionId), params)
        }
    }

    private suspend fun handleTerminalKill(params: KillTerminalCommandRequest): KillTerminalCommandResponse {
        trace(
            "terminal/kill session=$proxySessionId requestSession=${params.sessionId.value} terminalId=${params.terminalId}"
        )
        return runHostCall(
            invalidRequestMessage = "Invalid terminal/kill request",
            failureTrace = "terminal/kill/failed session=$proxySessionId terminalId=${params.terminalId}"
        ) {
            hostCapabilities.kill(hostSessionContext(params.sessionId), params)
        }
    }

    private fun selectApprovedPermissionOptionId(options: List<PermissionOption>): PermissionOptionId {
        return selectPermissionOptionId(
            options = options,
            preferredKinds = listOf(
                PermissionOptionKind.ALLOW_ONCE,
                PermissionOptionKind.ALLOW_ALWAYS
            )
        )
    }

    private fun selectRejectedPermissionOptionId(options: List<PermissionOption>): PermissionOptionId {
        return selectPermissionOptionId(
            options = options,
            preferredKinds = listOf(
                PermissionOptionKind.REJECT_ONCE,
                PermissionOptionKind.REJECT_ALWAYS
            )
        )
    }

    private fun selectPermissionOptionId(
        options: List<PermissionOption>,
        preferredKinds: List<PermissionOptionKind>
    ): PermissionOptionId {
        preferredKinds.forEach { preferred ->
            options.firstOrNull { it.kind == preferred }?.let { return it.optionId }
        }
        return options.firstOrNull()?.optionId ?: PermissionOptionId("abort")
    }

    private fun buildApprovalRequest(
        rawTitle: String,
        details: String,
        toolName: String,
        parsedArgs: AcpToolCallArgs?
    ): ToolApprovalRequest {
        val approvalType = when (parsedArgs) {
            is AcpToolCallArgs.Write -> ToolApprovalType.WRITE
            is AcpToolCallArgs.Edit -> ToolApprovalType.EDIT
            is AcpToolCallArgs.Bash -> ToolApprovalType.BASH
            else -> ToolSpecs.approvalTypeFor(toolName)
        }
        val payload = approvalPayload(parsedArgs)
        val title = approvalTitle(rawTitle, approvalType, parsedArgs)
        val resolvedDetails = approvalDetails(details, parsedArgs)
        return ToolApprovalRequest(
            type = approvalType,
            title = title,
            details = resolvedDetails,
            payload = payload
        )
    }

    private fun approvalPayload(parsedArgs: AcpToolCallArgs?): ToolApprovalPayload? {
        return when (parsedArgs) {
            is AcpToolCallArgs.Write -> WritePayload(
                parsedArgs.value.filePath,
                parsedArgs.value.content
            )

            is AcpToolCallArgs.Edit -> EditPayload(
                filePath = parsedArgs.value.filePath,
                oldString = parsedArgs.value.oldString,
                newString = parsedArgs.value.newString,
                replaceAll = parsedArgs.value.replaceAll
            )

            is AcpToolCallArgs.Bash -> BashPayload(
                parsedArgs.value.command,
                parsedArgs.value.description
            )

            else -> null
        }
    }

    private fun approvalTitle(
        rawTitle: String,
        approvalType: ToolApprovalType,
        parsedArgs: AcpToolCallArgs?
    ): String {
        return when (parsedArgs) {
            is AcpToolCallArgs.Write -> "Write ${Path.of(parsedArgs.value.filePath).name}?"
            is AcpToolCallArgs.Edit -> "Edit ${Path.of(parsedArgs.value.filePath).name}?"
            else -> rawTitle.ifBlank {
                when (approvalType) {
                    ToolApprovalType.BASH -> "Run shell command?"
                    ToolApprovalType.WRITE -> "Write file?"
                    ToolApprovalType.EDIT -> "Edit file?"
                    ToolApprovalType.GENERIC -> "Allow action?"
                }
            }
        }
    }

    private fun approvalDetails(details: String, parsedArgs: AcpToolCallArgs?): String {
        return when (parsedArgs) {
            is AcpToolCallArgs.Write -> parsedArgs.value.filePath
            is AcpToolCallArgs.Edit -> parsedArgs.value.filePath
            else -> details
        }
    }

    private fun permissionResponse(optionId: PermissionOptionId): RequestPermissionResponse {
        return RequestPermissionResponse(
            outcome = RequestPermissionOutcome.Selected(optionId)
        )
    }

    private fun permissionCancelledResponse(): RequestPermissionResponse {
        return RequestPermissionResponse(
            outcome = RequestPermissionOutcome.Cancelled
        )
    }

    private fun hostSessionContext(sessionId: SessionId): AcpHostSessionContext {
        return AcpHostSessionContext(sessionId = sessionId.value, cwd = sessionRoot)
    }

    private suspend inline fun <T> runHostCall(
        invalidRequestMessage: String,
        failureTrace: String,
        crossinline block: suspend () -> T
    ): T {
        return try {
            block()
        } catch (ex: IllegalArgumentException) {
            acpFail(ex.message ?: invalidRequestMessage)
        } catch (ex: Exception) {
            trace("$failureTrace error=${(ex.message ?: ex::class.simpleName.orEmpty()).logPreview()}")
            throw ex
        }
    }
}

private fun AgentSession?.currentAcpMode(): String? {
    return this?.externalAgentConfigOptions
        ?.firstOrNull { it.id == AcpConfigCategories.MODE || it.category == AcpConfigCategories.MODE }
        ?.currentValue
}
