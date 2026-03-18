package ee.carlrobert.codegpt.agent.external

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class AcpJsonRpcRequest(
    val id: JsonElement,
    val method: String,
    val params: JsonObject
)

internal data class AcpJsonRpcNotification(
    val method: String,
    val params: JsonObject
)

internal data class AcpJsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
) {
    companion object {
        const val METHOD_NOT_FOUND_CODE = -32601
    }
}

internal class AcpJsonRpcException(
    val error: AcpJsonRpcError
) : IllegalStateException(error.message)

private sealed interface AcpJsonRpcIncomingMessage {
    data class Response(
        val id: String,
        val result: JsonElement,
        val error: AcpJsonRpcError?
    ) : AcpJsonRpcIncomingMessage

    data class Request(val value: AcpJsonRpcRequest) : AcpJsonRpcIncomingMessage

    data class Notification(val value: AcpJsonRpcNotification) : AcpJsonRpcIncomingMessage
}

internal class AcpJsonRpcConnection(
    private val json: Json,
    private val process: Process,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val processName: String,
    private val onRequest: suspend (AcpJsonRpcRequest) -> JsonElement?,
    private val onNotification: suspend (AcpJsonRpcNotification) -> Unit
) {

    private val requestCounter = AtomicLong(0)
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val writeMutex = Mutex()

    fun isAlive(): Boolean = process.isAlive

    fun startReader() {
        scope.launch {
            val reader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
            try {
                while (isActive && process.isAlive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (service<ConfigurationSettings>().state.debugModeEnabled) {
                        logger.info("[$processName] $line")
                    }

                    when (val message = parseIncomingMessage(line)) {
                        null -> Unit
                        is AcpJsonRpcIncomingMessage.Response -> handleResponse(message)
                        is AcpJsonRpcIncomingMessage.Request -> reply(message.value)
                        is AcpJsonRpcIncomingMessage.Notification -> onNotification(message.value)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logger.warn("ACP reader loop failed for $processName", t)
            } finally {
                closePendingResponses(IllegalStateException("$processName ACP process exited"))
            }
        }
    }

    fun startStderrLogger() {
        scope.launch {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        logger.info("[$processName] $line")
                    }
                }
            }
        }
    }

    suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = requestCounter.incrementAndGet().toString()
        val response = CompletableDeferred<JsonElement>()
        pendingResponses[id] = response
        writePayload(requestPayload(id, method, params))
        return response.await().jsonObject
    }

    suspend fun notify(method: String, params: JsonObject) {
        writePayload(notificationPayload(method, params))
    }

    fun close() {
        closePendingResponses(CancellationException("ACP session closed"))
        process.destroy()
    }

    private suspend fun reply(request: AcpJsonRpcRequest) {
        val response = runCatching { onRequest(request) }.fold(
            onSuccess = { body ->
                if (body == null) {
                    errorResponse(request.id, -32601, "Method not found: ${request.method}")
                } else {
                    successResponse(request.id, body)
                }
            },
            onFailure = { error ->
                errorResponse(request.id, -32603, error.message ?: "Internal error")
            }
        )
        writePayload(response)
    }

    private fun handleResponse(response: AcpJsonRpcIncomingMessage.Response) {
        val pending = pendingResponses.remove(response.id) ?: return
        if (response.error != null) {
            pending.completeExceptionally(AcpJsonRpcException(response.error))
            return
        }
        pending.complete(response.result)
    }

    private fun parseIncomingMessage(line: String): AcpJsonRpcIncomingMessage? {
        val element = runCatching { json.parseToJsonElement(line) }
            .onFailure { logger.warn("Ignoring non-JSON ACP output from $processName: $line") }
            .getOrNull() ?: return null
        val obj = element.jsonObject
        return when {
            obj["result"] != null || obj["error"] != null -> {
                val responseId = obj["id"]?.jsonPrimitive?.content ?: return null
                AcpJsonRpcIncomingMessage.Response(
                    id = responseId,
                    result = obj["result"] ?: JsonObject(emptyMap()),
                    error = parseError(obj["error"])
                )
            }

            obj["method"] != null && obj["id"] != null -> {
                val method = obj["method"]?.jsonPrimitive?.content ?: return null
                AcpJsonRpcIncomingMessage.Request(
                    AcpJsonRpcRequest(
                        id = obj["id"] ?: return null,
                        method = method,
                        params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    )
                )
            }

            obj["method"] != null -> {
                val method = obj["method"]?.jsonPrimitive?.content ?: return null
                AcpJsonRpcIncomingMessage.Notification(
                    AcpJsonRpcNotification(
                        method = method,
                        params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    )
                )
            }

            else -> null
        }
    }

    private fun parseError(element: JsonElement?): AcpJsonRpcError? {
        val error = element as? JsonObject ?: return null
        return AcpJsonRpcError(
            code = error.int("code") ?: 0,
            message = error.string("message") ?: "Unknown JSON-RPC error",
            data = error["data"]
        )
    }

    private suspend fun writePayload(payload: JsonObject) {
        writeMutex.withLock {
            val serializedPayload = json.encodeToString(JsonObject.serializer(), payload)
            if (service<ConfigurationSettings>().state.debugModeEnabled) {
                logger.info("[$processName] $serializedPayload")
            }

            process.outputStream.write(
                (serializedPayload + "\n").toByteArray(StandardCharsets.UTF_8)
            )
            process.outputStream.flush()
        }
    }

    private fun closePendingResponses(error: Throwable) {
        pendingResponses.values.forEach { pending ->
            pending.completeExceptionally(error)
        }
        pendingResponses.clear()
    }

    private fun requestPayload(id: String, method: String, params: JsonObject): JsonObject {
        return buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
    }

    private fun notificationPayload(method: String, params: JsonObject): JsonObject {
        return buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
    }

    private fun successResponse(id: JsonElement, result: JsonElement): JsonObject {
        return buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", id)
            put("result", result)
        }
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String): JsonObject {
        return buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", JsonPrimitive(code))
                    put("message", JsonPrimitive(message))
                }
            )
        }
    }
}
