package ee.carlrobert.codegpt.mcp

import com.fasterxml.jackson.databind.ObjectMapper

object McpToolArgumentsParser {

    private val objectMapper = ObjectMapper()

    data class ParseResult(
        val arguments: Map<String, Any?> = emptyMap(),
        val error: String? = null
    )

    fun parse(argumentsJson: String?): ParseResult {
        if (argumentsJson.isNullOrBlank()) {
            return ParseResult()
        }

        return try {
            val raw = (objectMapper.readValue(argumentsJson, Map::class.java) as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (key, value) ->
                    val strKey = key?.toString() ?: return@mapNotNull null
                    strKey to value
                }
                ?.toMap()
                ?: return ParseResult()
            ParseResult(
                arguments = raw.mapValues { (_, value) ->
                when (value) {
                    is Double -> if (value % 1 == 0.0) value.toInt() else value
                    else -> value
                }
            }
            )
        } catch (exception: Exception) {
            ParseResult(
                error = exception.message ?: "Invalid tool arguments JSON"
            )
        }
    }
}
