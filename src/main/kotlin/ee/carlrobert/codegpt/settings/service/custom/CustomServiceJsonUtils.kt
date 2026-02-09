package ee.carlrobert.codegpt.settings.service.custom

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

object CustomServiceJsonUtils {

    private val objectMapper = ObjectMapper()

    fun toPrettyJson(value: Map<*, *>): String = try {
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
    } catch (_: Exception) {
        "{}"
    }

    fun parseHeadersJson(rawJson: String): Map<String, String> {
        if (rawJson.isBlank()) {
            return emptyMap()
        }

        return try {
            val parsed =
                objectMapper.readValue(rawJson, object : TypeReference<Map<String, Any?>>() {})
            buildMap {
                parsed.forEach { (rawKey, rawValue) ->
                    val key = rawKey.trim()
                    if (key.isEmpty()) {
                        throw IllegalArgumentException("Header key cannot be empty.")
                    }
                    if (rawValue == null) {
                        throw IllegalArgumentException("Header '$key' cannot be null.")
                    }
                    if (rawValue is Map<*, *> || rawValue is List<*>) {
                        throw IllegalArgumentException("Header '$key' must be string/number/boolean.")
                    }
                    put(key, rawValue.toString())
                }
            }
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalArgumentException(summarizeParseError(exception))
        }
    }

    fun parseBodyJson(rawJson: String): Map<String, Any?> {
        if (rawJson.isBlank()) {
            return emptyMap()
        }

        return try {
            objectMapper.readValue(rawJson, object : TypeReference<Map<String, Any?>>() {})
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalArgumentException(summarizeParseError(exception))
        }
    }

    fun toBodyDisplayValue(value: Any?): String = when (value) {
        null -> ""
        is Map<*, *>, is List<*> -> try {
            objectMapper.writeValueAsString(value)
        } catch (_: Exception) {
            value.toString()
        }

        else -> value.toString()
    }

    fun parseBodyValue(type: CustomServiceBodyValueType, value: String): Any? {
        val trimmed = value.trim()
        return when (type) {
            CustomServiceBodyValueType.STRING -> value
            CustomServiceBodyValueType.PLACEHOLDER -> parsePlaceholderValue(trimmed)
            CustomServiceBodyValueType.NUMBER -> parseBodyNumber(trimmed)
            CustomServiceBodyValueType.BOOLEAN -> parseBoolean(trimmed)
            CustomServiceBodyValueType.NULL -> null
            CustomServiceBodyValueType.OBJECT -> parseJsonObject(trimmed)
            CustomServiceBodyValueType.ARRAY -> parseJsonArray(trimmed)
        }
    }

    fun parseBodyValue(value: String): Any? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        if (trimmed.equals("null", ignoreCase = true)) {
            return null
        }
        if (trimmed.equals("true", ignoreCase = true) || trimmed.equals(
                "false",
                ignoreCase = true
            )
        ) {
            return trimmed.toBooleanStrictOrNull() ?: value
        }

        try {
            return if ('.' in trimmed || 'e' in trimmed || 'E' in trimmed) {
                trimmed.toDouble()
            } else {
                trimmed.toIntOrNull() ?: trimmed.toLong()
            }
        } catch (_: NumberFormatException) {
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return try {
                parseJsonObject(trimmed)
            } catch (_: IllegalArgumentException) {
                value
            }
        }

        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return try {
                parseJsonArray(trimmed)
            } catch (_: IllegalArgumentException) {
                value
            }
        }

        return value
    }

    private fun summarizeParseError(exception: Exception): String {
        val message = exception.message
        if (message.isNullOrBlank()) {
            return "Malformed JSON."
        }
        val newlineIndex = message.indexOf('\n')
        return if (newlineIndex > 0) message.substring(0, newlineIndex) else message
    }

    private fun parseBodyNumber(value: String): Number {
        if (value.isEmpty()) {
            throw IllegalArgumentException("Number value cannot be empty.")
        }

        return try {
            if ('.' in value || 'e' in value || 'E' in value) {
                value.toDouble()
            } else {
                value.toIntOrNull() ?: value.toLong()
            }
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Invalid number format.")
        }
    }

    private fun parsePlaceholderValue(value: String): String {
        if (value.isEmpty()) {
            throw IllegalArgumentException("Placeholder is required.")
        }
        if (!CustomServicePlaceholders.containsCode(value)) {
            throw IllegalArgumentException("Unknown placeholder value.")
        }
        return value.trim()
    }

    private fun parseBoolean(value: String): Boolean = when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        else -> throw IllegalArgumentException("Boolean must be 'true' or 'false'.")
    }

    private fun parseJsonObject(value: String): Any {
        if (value.isBlank()) {
            throw IllegalArgumentException("Object value cannot be empty.")
        }

        return try {
            val parsed = objectMapper.readValue(value, Any::class.java)
            if (parsed !is Map<*, *>) {
                throw IllegalArgumentException("Value must be a JSON object.")
            }
            parsed
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid JSON object.")
        }
    }

    private fun parseJsonArray(value: String): Any {
        if (value.isBlank()) {
            throw IllegalArgumentException("Array value cannot be empty.")
        }

        return try {
            val parsed = objectMapper.readValue(value, Any::class.java)
            if (parsed !is List<*>) {
                throw IllegalArgumentException("Value must be a JSON array.")
            }
            parsed
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid JSON array.")
        }
    }
}
