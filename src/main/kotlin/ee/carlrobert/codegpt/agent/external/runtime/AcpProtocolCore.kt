package ee.carlrobert.codegpt.agent.external.runtime

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.rpc.*
import com.agentclientprotocol.transport.Transport
import ee.carlrobert.codegpt.agent.external.acpcompat.ProtocolOptions
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal class AcpProtocolCore(
    parentScope: CoroutineScope,
    private val transport: Transport,
    json: Json,
    private val options: ProtocolOptions
) {
    private val trace = options.trace
    private val scope = CoroutineScope(
        parentScope.coroutineContext
                + SupervisorJob(parentScope.coroutineContext[Job])
                + CoroutineName(options.protocolDebugName)
    )
    private val requestsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "${options.protocolDebugName}.requests").apply {
            isDaemon = true
        }
    }
    private val requestsDispatcher = requestsExecutor.asCoroutineDispatcher()
    private val requestsScope = CoroutineScope(
        scope.coroutineContext
                + SupervisorJob(scope.coroutineContext[Job])
                + requestsDispatcher
                + CoroutineName("${options.protocolDebugName}.requests")
    )
    private val state = AcpProtocolState()
    private val incomingRequests = AcpIncomingRequestManager(state, transport, json, requestsScope, trace)
    private val outgoingRequests = AcpOutgoingRequestManager(state, transport, json, options, trace)
    private val messagePump = AcpMessagePump(
        scope,
        transport,
        options.protocolDebugName,
        onMessage = ::handleIncomingMessage
    )

    fun start() {
        incomingRequests.installMetaHandlers()
        messagePump.start()
    }

    suspend fun sendRequestRaw(
        method: MethodName,
        params: JsonElement? = null
    ): JsonElement {
        return outgoingRequests.sendRequestRaw(method, params)
    }

    fun sendNotificationRaw(
        method: AcpMethod.AcpNotificationMethod<*>,
        params: JsonElement? = null
    ) {
        val notification = JsonRpcNotification(method = method.methodName, params = params)
        trace?.invoke("-> ${notification.traceSummary()}")
        transport.send(notification)
    }

    fun setRequestHandlerRaw(
        method: AcpMethod.AcpRequestResponseMethod<*, *>,
        additionalContext: CoroutineContext = EmptyCoroutineContext,
        handler: suspend (JsonRpcRequest) -> JsonElement?
    ) {
        incomingRequests.setRequestHandlerRaw(method, additionalContext, handler)
    }

    fun setNotificationHandlerRaw(
        method: AcpMethod.AcpNotificationMethod<*>,
        additionalContext: CoroutineContext = EmptyCoroutineContext,
        handler: suspend (JsonRpcNotification) -> Unit
    ) {
        incomingRequests.setNotificationHandlerRaw(method, additionalContext, handler)
    }

    fun close() {
        transport.close()
        val message = "Protocol closed"
        incomingRequests.cancelPendingIncomingRequests(CancellationException(message))
        outgoingRequests.cancelPendingOutgoingRequests(CancellationException(message))
        scope.cancel(message)
        requestsDispatcher.close()
    }

    private suspend fun handleIncomingMessage(message: JsonRpcMessage) {
        trace?.invoke("<- ${message.traceSummary()}")
        when (message) {
            is JsonRpcNotification -> incomingRequests.handleNotification(message)
            is JsonRpcRequest -> incomingRequests.handleRequest(message)
            is JsonRpcResponse -> outgoingRequests.handleResponse(message)
        }
    }
}
