package ee.carlrobert.codegpt.agent.external.runtime

import com.agentclientprotocol.rpc.JsonRpcErrorCode
import ee.carlrobert.codegpt.agent.external.acpcompat.AcpExpectedError
import ee.carlrobert.codegpt.agent.external.acpcompat.JsonRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class JsonRpcRequestContextElement :
    AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<JsonRpcRequestContextElement>
}

internal class JsonRpcIncomingRequestCanceledException(
    message: String,
    val data: JsonElement? = null
) : CancellationException(message)

internal fun convertJsonRpcExceptionIfPossible(jsonRpcException: JsonRpcException): Exception {
    return when (jsonRpcException.code) {
        JsonRpcErrorCode.PARSE_ERROR.code -> SerializationException(
            jsonRpcException.message,
            jsonRpcException
        )

        JsonRpcErrorCode.INVALID_PARAMS.code -> AcpExpectedError(
            jsonRpcException.message ?: "Invalid params"
        )

        JsonRpcErrorCode.CANCELLED.code -> CancellationException(
            jsonRpcException.message ?: "Cancelled on the counterpart side",
            jsonRpcException
        )

        else -> jsonRpcException
    }
}
