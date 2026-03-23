package ee.carlrobert.codegpt.agent.external.runtime

import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class AcpProtocolState {
    private val requestIdCounter = AtomicInteger(0)

    val pendingOutgoingRequests = ConcurrentHashMap<RequestId, CompletableDeferred<JsonElement>>()
    val pendingIncomingRequests = ConcurrentHashMap<RequestId, Job>()
    val requestHandlers = ConcurrentHashMap<MethodName, suspend (JsonRpcRequest) -> JsonElement?>()
    val notificationHandlers =
        ConcurrentHashMap<MethodName, suspend (JsonRpcNotification) -> Unit>()

    fun nextRequestId(): RequestId = RequestId.Companion.create(requestIdCounter.incrementAndGet())
}
