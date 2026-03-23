package ee.carlrobert.codegpt.agent.external.runtime

import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import kotlinx.serialization.json.JsonElement

internal fun JsonRpcMessage.traceSummary(): String {
    return when (this) {
        is JsonRpcRequest -> "request id=$id method=$method params=${params.traceSummary()}"
        is JsonRpcNotification -> "notification method=$method params=${params.traceSummary()}"
        is JsonRpcResponse -> "response id=$id ${error.traceSummary(result)}"
    }
}

private fun JsonRpcError?.traceSummary(result: JsonElement?): String {
    return if (this == null) {
        "result=${result.traceSummary()}"
    } else {
        "errorCode=$code errorMessage=${message.orEmpty().singleLine()} errorData=${data.traceSummary()}"
    }
}

private fun JsonElement?.traceSummary(limit: Int = 400): String {
    return this?.toString()?.singleLine()?.take(limit) ?: "null"
}

private fun String.singleLine(): String {
    return replace(Regex("\\s+"), " ").trim()
}
