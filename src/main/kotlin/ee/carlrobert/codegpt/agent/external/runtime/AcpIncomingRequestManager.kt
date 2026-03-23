package ee.carlrobert.codegpt.agent.external.runtime

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CancelRequestNotification
import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.Transport
import ee.carlrobert.codegpt.agent.external.acpcompat.AcpExpectedError
import ee.carlrobert.codegpt.agent.external.decodeOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.CoroutineContext

private val incomingLogger = KotlinLogging.logger {}

internal class AcpIncomingRequestManager(
    private val state: AcpProtocolState,
    private val transport: Transport,
    private val json: Json,
    private val requestsScope: CoroutineScope,
    private val trace: ((String) -> Unit)?
) {

    fun installMetaHandlers() {
        state.notificationHandlers[AcpMethod.MetaMethods.CancelRequest.methodName] =
            { notification ->
                val request = notification.params.decodeOrNull<CancelRequestNotification>(json)
                when {
                    request == null -> incomingLogger.warn { "Received CancelRequest with invalid payload" }
                    else -> {
                        val requestJob = state.pendingIncomingRequests.remove(request.requestId)
                        if (requestJob == null) {
                            incomingLogger.warn { "Received CancelRequest for unknown request: ${request.requestId}" }
                        } else {
                            requestJob.cancel(
                                JsonRpcIncomingRequestCanceledException(
                                    request.message ?: "Cancelled by the counterpart",
                                )
                            )
                        }
                    }
                }
            }
    }

    fun setRequestHandlerRaw(
        method: AcpMethod.AcpRequestResponseMethod<*, *>,
        additionalContext: CoroutineContext,
        handler: suspend (JsonRpcRequest) -> JsonElement?
    ) {
        state.requestHandlers[method.methodName] = { request ->
            withContext(additionalContext) {
                handler(request)
            }
        }
    }

    fun setNotificationHandlerRaw(
        method: AcpMethod.AcpNotificationMethod<*>,
        additionalContext: CoroutineContext,
        handler: suspend (JsonRpcNotification) -> Unit
    ) {
        state.notificationHandlers[method.methodName] = { notification ->
            withContext(additionalContext) {
                handler(notification)
            }
        }
    }

    fun handleRequest(request: JsonRpcRequest) {
        val requestId = request.id
        requestsScope.launch {
            processRequest(request)
        }.also { job ->
            state.pendingIncomingRequests[requestId] = job
        }.invokeOnCompletion {
            state.pendingIncomingRequests.remove(requestId)
        }
    }

    suspend fun handleNotification(notification: JsonRpcNotification) {
        val handler = state.notificationHandlers[notification.method]
        if (handler != null) {
            try {
                handler(notification)
            } catch (e: Exception) {
                incomingLogger.error(e) { "Error handling notification ${notification.method}" }
            }
        } else {
            incomingLogger.debug { "No handler for notification: ${notification.method}" }
        }
    }

    fun cancelPendingIncomingRequests(ce: CancellationException? = null) {
        val requests = state.pendingIncomingRequests.toMap()
        state.pendingIncomingRequests.clear()
        for ((requestId, job) in requests) {
            incomingLogger.trace { "Canceling pending incoming request: $requestId" }
            job.cancel(ce)
        }
    }

    private suspend fun processRequest(request: JsonRpcRequest) {
        val handler = state.requestHandlers[request.method]
        val response = if (handler == null) {
                JsonRpcResponse(
                    request.id,
                    null,
                    JsonRpcError(
                        JsonRpcErrorCode.METHOD_NOT_FOUND.code,
                        "Method not supported: ${request.method}"
                    )
                )
        } else {
            buildResponse(request, handler)
        }
        if (response != null) {
            trace?.invoke("-> ${response.traceSummary()}")
            transport.send(response)
        }
    }

    private suspend fun buildResponse(
        request: JsonRpcRequest,
        handler: suspend (JsonRpcRequest) -> JsonElement?
    ): JsonRpcResponse? {
        return try {
            val result = withContext(JsonRpcRequestContextElement()) {
                handler(request)
            }
            JsonRpcResponse(request.id, result, null)
        } catch (e: AcpExpectedError) {
            incomingLogger.trace(e) { "Expected error on '${request.method}'" }
            errorResponse(
                request.id,
                JsonRpcErrorCode.INVALID_PARAMS,
                e.message ?: "Invalid params"
            )
        } catch (e: SerializationException) {
            incomingLogger.trace(e) { "Serialization error on ${request.method}" }
            errorResponse(
                request.id,
                JsonRpcErrorCode.PARSE_ERROR,
                e.message ?: "Serialization error"
            )
        } catch (ce: CancellationException) {
            incomingLogger.trace(ce) { "Incoming request cancelled: ${request.method}" }
            if (ce is JsonRpcIncomingRequestCanceledException) {
                null
            } else {
                errorResponse(request.id, JsonRpcErrorCode.CANCELLED, ce.message ?: "Cancelled")
            }
        } catch (e: Exception) {
            incomingLogger.error(e) { "Exception on ${request.method}" }
            errorResponse(
                request.id,
                JsonRpcErrorCode.INTERNAL_ERROR,
                e.message ?: "Internal error"
            )
        }
    }

    private fun errorResponse(
        requestId: RequestId,
        code: JsonRpcErrorCode,
        message: String
    ): JsonRpcResponse {
        return JsonRpcResponse(requestId, null, JsonRpcError(code.code, message))
    }
}
