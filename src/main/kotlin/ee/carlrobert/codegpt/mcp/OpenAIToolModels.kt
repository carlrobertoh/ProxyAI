package ee.carlrobert.codegpt.mcp

data class OpenAITool(
    var type: String? = null,
    var function: OpenAIToolFunction? = null,
)

data class OpenAIToolFunction(
    var name: String? = null,
    var description: String? = null,
    var parameters: OpenAIToolFunctionParameters? = null,
)

data class OpenAIToolFunctionParameters(
    var type: String? = null,
    var properties: Map<String, Any> = emptyMap(),
    var required: List<String> = emptyList(),
)
