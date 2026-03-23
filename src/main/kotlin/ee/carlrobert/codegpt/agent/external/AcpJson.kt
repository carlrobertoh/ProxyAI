package ee.carlrobert.codegpt.agent.external

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

internal inline fun <reified T> JsonElement?.decodeOrNull(json: Json): T? {
    return when (this) {
        null -> null
        is JsonPrimitive -> {
            if (!isString) {
                runCatching { json.decodeFromJsonElement<T>(this) }.getOrNull()
            } else {
                runCatching { json.decodeFromString<T>(content) }.getOrNull()
            }
        }

        else -> runCatching { json.decodeFromJsonElement<T>(this) }.getOrNull()
    }
}

internal fun JsonElement?.toPayloadString(): String {
    return when (this) {
        null -> ""
        is JsonPrimitive -> if (isString) content else toString()
        else -> toString()
    }
}
