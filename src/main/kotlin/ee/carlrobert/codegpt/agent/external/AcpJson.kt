package ee.carlrobert.codegpt.agent.external

import kotlinx.serialization.json.*

internal fun JsonObject.string(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        when (val element = this[key]) {
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }
    }
}

internal fun JsonObject.commandString(): String? {
    return listOf("command", "cmd").firstNotNullOfOrNull { key ->
        when (val element = this[key]) {
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonArray -> element.mapNotNull { item ->
                (item as? JsonPrimitive)?.contentOrNull
            }.takeIf { it.isNotEmpty() }?.joinToString(" ")

            else -> null
        }
    }
}

internal fun JsonObject.boolean(vararg keys: String): Boolean? {
    return keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}

internal fun JsonObject.int(vararg keys: String): Int? {
    return keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.intOrNull
    }
}

internal fun JsonObject.firstLocationPath(): String? {
    return (this["locations"] as? JsonArray)
        ?.firstNotNullOfOrNull { (it as? JsonObject)?.string("path") }
}

internal fun JsonObject.titlePath(): String? {
    val title = string("title") ?: return null
    val firstSpaceIndex = title.indexOf(' ')
    if (firstSpaceIndex < 0 || firstSpaceIndex >= title.lastIndex) {
        return null
    }
    val path = title.substring(firstSpaceIndex + 1).trim()
    return path.takeIf { it.startsWith("/") }
}

internal fun JsonObject.firstChangePath(): String? {
    return (this["changes"] as? JsonObject)?.keys?.firstOrNull()
}

internal fun JsonObject.firstChangeContent(): String? {
    return (this["changes"] as? JsonObject)?.values?.firstNotNullOfOrNull { change ->
        (change as? JsonObject)?.string("content")
    }
}

internal fun JsonElement?.asJsonArrayOrEmpty(): JsonArray {
    return this as? JsonArray ?: JsonArray(emptyList())
}

internal fun JsonElement?.asJsonObjectOrNull(json: Json): JsonObject? {
    return when (this) {
        is JsonObject -> this
        is JsonPrimitive -> {
            if (!isString) {
                return null
            }
            runCatching { json.parseToJsonElement(content) }.getOrNull() as? JsonObject
        }

        else -> null
    }
}

internal fun JsonElement?.toPayloadString(): String {
    return when (this) {
        null -> ""
        is JsonPrimitive -> if (isString) content else toString()
        else -> toString()
    }
}
