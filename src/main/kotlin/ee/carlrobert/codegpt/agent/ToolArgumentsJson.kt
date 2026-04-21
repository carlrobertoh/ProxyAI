package ee.carlrobert.codegpt.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun normalizeToolArgumentsJson(rawArgs: String?): String? {
    val payload = rawArgs?.trim().orEmpty()
    if (payload.isBlank()) {
        return null
    }

    val element = runCatching {
        agentJson.parseToJsonElement(payload)
    }.getOrNull() ?: return null

    return normalizeToolArgumentsElement(element)?.toString()
}

private fun normalizeToolArgumentsElement(element: JsonElement): JsonObject? {
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
                        agentJson.parseToJsonElement(nestedPayload)
                    }.getOrNull() ?: return null
                    normalizeToolArgumentsElement(nested)
                }
            }
        }

        else -> null
    }
}
