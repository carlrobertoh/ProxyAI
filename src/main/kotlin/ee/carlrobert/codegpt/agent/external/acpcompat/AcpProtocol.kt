package ee.carlrobert.codegpt.agent.external.acpcompat

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AcpNotification
import com.agentclientprotocol.model.AcpRequest
import com.agentclientprotocol.model.AcpResponse
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.transport.Transport
import ee.carlrobert.codegpt.agent.external.runtime.AcpProtocolCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AcpExpectedError(message: String) : Exception(message)

fun acpFail(message: String): Nothing = throw AcpExpectedError(message)

class JsonRpcException(
    val code: Int,
    message: String,
    val data: JsonElement? = null
) : Exception(message)

open class ProtocolOptions(
    val gracefulRequestCancellationTimeout: Duration = 1.seconds,
    val protocolDebugName: String = AcpProtocol::class.simpleName!!,
    val outboundPayloadAugmenter: (MethodName, JsonElement?) -> JsonElement? = { _, payload -> payload },
    val inboundPayloadNormalizer: (MethodName, JsonElement?) -> JsonElement? = { _, payload -> payload },
    val trace: ((String) -> Unit)? = null
)

class AcpProtocol(
    parentScope: CoroutineScope,
    transport: Transport,
    val options: ProtocolOptions = ProtocolOptions()
) {
    internal val json: Json = ACPJson

    internal val core = AcpProtocolCore(parentScope, transport, json, options)

    fun start() = core.start()

    fun close() = core.close()

    override fun toString(): String = "Protocol(${options.protocolDebugName})"
}

internal suspend inline fun <reified TRequest : AcpRequest, reified TResponse : AcpResponse> AcpProtocol.sendRequest(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    request: TRequest?
): TResponse {
    val params = options.outboundPayloadAugmenter(
        method.methodName,
        request?.let { json.encodeToJsonElement(request) }
    )
    val responseJson = core.sendRequestRaw(method.methodName, params)
    return json.decodeFromJsonElement(
        options.inboundPayloadNormalizer(method.methodName, responseJson) ?: JsonNull
    )
}

internal inline fun <reified TNotification : AcpNotification> AcpProtocol.sendNotification(
    method: AcpMethod.AcpNotificationMethod<TNotification>,
    notification: TNotification? = null
) {
    val params = notification?.let { json.encodeToJsonElement(notification) }
    core.sendNotificationRaw(method, params)
}

internal inline fun <reified TRequest : AcpRequest, reified TResponse : AcpResponse> AcpProtocol.setRequestHandler(
    method: AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend (TRequest) -> TResponse
) {
    core.setRequestHandlerRaw(method, additionalContext) { request ->
        val requestParams = decodeAcpPayload<TRequest>(
            json,
            options.inboundPayloadNormalizer(method.methodName, request.params)
        )
        val responseObject = handler(requestParams)
        json.encodeToJsonElement(responseObject)
    }
}

internal inline fun <reified TNotification : AcpNotification> AcpProtocol.setNotificationHandler(
    method: AcpMethod.AcpNotificationMethod<TNotification>,
    additionalContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend (TNotification) -> Unit
) {
    core.setNotificationHandlerRaw(method, additionalContext) { notification ->
        val notificationParams = decodeAcpPayload<TNotification>(
            json,
            options.inboundPayloadNormalizer(method.methodName, notification.params)
        )
        handler(notificationParams)
    }
}

internal suspend inline operator fun <reified TRequest : AcpRequest, reified TResponse : AcpResponse> AcpMethod.AcpRequestResponseMethod<TRequest, TResponse>.invoke(
    protocol: AcpProtocol,
    request: TRequest
): TResponse {
    return protocol.sendRequest(this, request)
}

internal inline operator fun <reified TNotification : AcpNotification> AcpMethod.AcpNotificationMethod<TNotification>.invoke(
    protocol: AcpProtocol,
    notification: TNotification
) {
    return protocol.sendNotification(this, notification)
}

internal inline fun <reified T> decodeAcpPayload(
    json: Json,
    payload: JsonElement?
): T {
    return when (payload) {
        null -> json.decodeFromJsonElement(JsonNull)
        is JsonPrimitive -> {
            if (payload.isString) {
                json.decodeFromString(payload.content)
            } else {
                json.decodeFromJsonElement(payload)
            }
        }

        else -> json.decodeFromJsonElement(payload)
    }
}
