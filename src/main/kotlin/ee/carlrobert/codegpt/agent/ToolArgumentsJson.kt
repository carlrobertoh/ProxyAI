package ee.carlrobert.codegpt.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val toolArgumentsJson = Json {
    ignoreUnknownKeys = true
}

internal fun normalizeToolArgumentsJson(rawArgs: String?): String? {
    val payload = rawArgs?.trim().orEmpty()
    if (payload.isBlank()) {
        return null
    }

    val element = runCatching {
        toolArgumentsJson.parseToJsonElement(payload)
    }.getOrNull() ?: return null

    return normalizeToolArgumentsElement(element)?.toString()
}

private tailrec fun normalizeToolArgumentsElement(element: JsonElement): JsonObject? {
    return when (element) {
        is JsonObject -> element
        is JsonPrimitive -> {
            if (!element.isString) {
                null
            } else {
                val nestedPayload = element.contentOrNull?.trim().orEmpty()
                if (nestedPayload.isBlank()) {
                    null
                } else {
                    val nested = runCatching {
                        toolArgumentsJson.parseToJsonElement(nestedPayload)
                    }.getOrNull() ?: return null
                    normalizeToolArgumentsElement(nested)
                }
            }
        }

        else -> null
    }
}
