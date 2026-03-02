package ee.carlrobert.codegpt.agent.clients

import com.fasterxml.jackson.databind.JsonNode
import ee.carlrobert.codegpt.codecompletions.InfillRequest
import ee.carlrobert.codegpt.completions.CompletionError
import ee.carlrobert.codegpt.util.JsonMapper
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

internal const val MAX_COMPLETION_TOKENS = 128
internal const val CUSTOM_SERVICE_API_KEY_PLACEHOLDER = "${'$'}CUSTOM_SERVICE_API_KEY"

internal interface CodeCompletionCapable : AutoCloseable {
    suspend fun getCodeCompletion(infillRequest: InfillRequest): String
}

internal class CodeCompletionClientException(
    val statusCode: Int,
    val body: String
) : RuntimeException("HTTP $statusCode: $body")

internal fun Throwable.toCompletionError(): CompletionError {
    return when (this) {
        is CodeCompletionClientException -> CompletionError(
            message = extractErrorMessage(body).ifBlank {
                "Request failed with status $statusCode"
            }
        )

        else -> CompletionError(message ?: "Request failed")
    }
}

internal suspend fun postCompletionJson(
    client: HttpClient,
    url: String,
    headers: Map<String, String>,
    body: String
): String {
    return client.preparePost(url) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        headers.forEach { (key, value) -> header(key, value) }
        setBody(body)
    }.execute { response ->
        val payload = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw CodeCompletionClientException(response.status.value, payload)
        }
        payload
    }
}

internal fun parseOpenAITextCompletion(payload: String): String {
    val root = JsonMapper.mapper.readTree(payload)
    val choice = root.path("choices").firstOrNull() ?: return ""
    return choice.path("text").asText("")
}

internal fun parseOpenAIChatCompletion(payload: String): String {
    val root = JsonMapper.mapper.readTree(payload)
    val choice = root.path("choices").firstOrNull() ?: return ""
    return choice.path("message").path("content").asText()
        .ifBlank { choice.path("delta").path("content").asText() }
}

internal fun parseOllamaCompletion(payload: String): String {
    val root = JsonMapper.mapper.readTree(payload)
    return root.path("response").asText()
        .ifBlank { root.path("message").path("content").asText() }
}

internal fun parseLlamaCompletion(payload: String): String {
    return JsonMapper.mapper.readTree(payload).path("content").asText("")
}

internal fun buildOpenAIStyleCompletionBody(model: String, infillRequest: InfillRequest): String {
    return JsonMapper.mapper.writeValueAsString(
        mapOf(
            "model" to model,
            "prompt" to infillRequest.prefix,
            "suffix" to infillRequest.suffix,
            "stream" to false,
            "max_tokens" to MAX_COMPLETION_TOKENS,
            "temperature" to 0.0,
            "frequency_penalty" to 0.0,
            "presence_penalty" to 0.0,
            "stop" to infillRequest.stopTokens.ifEmpty { null }
        ).filterValues { it != null }
    )
}

private fun JsonNode.firstOrNull(): JsonNode? {
    return if (isArray && size() > 0) get(0) else null
}

private fun extractErrorMessage(body: String): String {
    return runCatching {
        val root = JsonMapper.mapper.readTree(body)
        root.path("error").path("message").asText()
            .ifBlank { root.path("message").asText() }
            .ifBlank { body }
    }.getOrDefault(body)
}
