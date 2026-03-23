package ee.carlrobert.codegpt.agent.external.runtime

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CancelRequestNotification
import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.Transport
import ee.carlrobert.codegpt.agent.external.acpcompat.JsonRpcException
import ee.carlrobert.codegpt.agent.external.acpcompat.ProtocolOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement

private val outgoingLogger = KotlinLogging.logger {}

internal class AcpOutgoingRequestManager(
    private val state: AcpProtocolState,
    private val transport: Transport,
    private val json: Json,
    private val options: ProtocolOptions,
    private val trace: ((String) -> Unit)?
) {

    suspend fun sendRequestRaw(
        method: MethodName,
        params: JsonElement? = null
    ): JsonElement {
        val requestId = state.nextRequestId()
        val deferred = CompletableDeferred<JsonElement>()
        state.pendingOutgoingRequests[requestId] = deferred

        try {
            val request = JsonRpcRequest(requestId, method, params)
            trace?.invoke("-> ${request.traceSummary()}")
            transport.send(request)
            return deferred.await()
        } catch (jsonRpcException: JsonRpcException) {
            throw convertJsonRpcExceptionIfPossible(jsonRpcException)
        } catch (ce: CancellationException) {
            outgoingLogger.trace(ce) {
                "Request cancelled on this side. Sending CancelRequest notification."
            }
            withContext(NonCancellable) {
                sendCancellationNotification(requestId, ce.message)

                if (!deferred.isCancelled) {
                    waitForGracefulCancellation(requestId, deferred)
                    deferred.cancel()
                }
            }
            throw ce
        } finally {
            state.pendingOutgoingRequests.remove(requestId)
        }
    }

    fun handleResponse(response: JsonRpcResponse) {
        val deferred = state.pendingOutgoingRequests.remove(response.id)
        if (deferred != null) {
            val responseError = response.error
            if (responseError != null) {
                deferred.completeExceptionally(
                    JsonRpcException(responseError.code, responseError.message, responseError.data)
                )
            } else {
                deferred.complete(response.result ?: JsonNull)
            }
        } else {
            outgoingLogger.warn { "Received response for unknown request ID: ${response.id}" }
        }
    }

    fun cancelPendingOutgoingRequests(ce: CancellationException? = null) {
        val requests = state.pendingOutgoingRequests.toMap()
        state.pendingOutgoingRequests.clear()
        for ((requestId, deferred) in requests) {
            outgoingLogger.trace { "Canceling pending outgoing request: $requestId" }
            deferred.cancel(ce)
        }
    }

    private fun sendCancellationNotification(
        requestId: RequestId,
        message: String?
    ) {
        val notification = JsonRpcNotification(
            method = AcpMethod.MetaMethods.CancelRequest.methodName,
            params = json.encodeToJsonElement(CancelRequestNotification(requestId, message))
        )
        trace?.invoke("-> ${notification.traceSummary()}")
        transport.send(notification)
    }

    private suspend fun waitForGracefulCancellation(
        requestId: RequestId,
        deferred: CompletableDeferred<JsonElement>
    ) {
        try {
            withTimeout(options.gracefulRequestCancellationTimeout) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            outgoingLogger.trace(e) {
                "Timed out waiting for graceful cancellation response for request: $requestId"
            }
        } catch (ce: CancellationException) {
            outgoingLogger.trace(ce) {
                "Graceful cancellation response received for request: $requestId"
            }
        } catch (e: JsonRpcException) {
            val convertedException = convertJsonRpcExceptionIfPossible(e)
            if (convertedException is CancellationException) {
                outgoingLogger.trace(convertedException) {
                    "Graceful cancellation response received for request: $requestId"
                }
            } else {
                outgoingLogger.warn(convertedException) {
                    "Unexpected error while waiting for graceful cancellation response for request: $requestId"
                }
            }
        } catch (e: Exception) {
            outgoingLogger.warn(e) {
                "Unexpected error while waiting for graceful cancellation response for request: $requestId"
            }
        }
    }
}
