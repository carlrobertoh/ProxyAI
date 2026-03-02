package ee.carlrobert.codegpt.mcp

object McpToolConverter {

    fun convertToOpenAITool(mcpTool: McpTool): OpenAITool {
        val tool = OpenAITool()
        tool.type = "function"
        tool.function = convertToOpenAIFunction(mcpTool)
        return tool
    }

    private fun convertToOpenAIFunction(mcpTool: McpTool): OpenAIToolFunction {
        val function = OpenAIToolFunction()
        function.name = mcpTool.name
        function.description = mcpTool.description

        function.parameters = if (mcpTool.schema.isNotEmpty()) {
            convertSchemaToParameters(mcpTool.schema)
        } else {
            val emptyParams = OpenAIToolFunctionParameters()
            emptyParams.type = "object"
            emptyParams.properties = mutableMapOf<String, Any>()
            emptyParams
        }

        return function
    }

    private fun convertSchemaToParameters(schema: Map<String, Any>): OpenAIToolFunctionParameters {
        val parameters = OpenAIToolFunctionParameters()
        parameters.type = "object"

        val properties = mutableMapOf<String, Any>()
        val required = mutableListOf<String>()

        when {
            schema.containsKey("type") && schema["type"] == "object" -> {
                properties.putAll(toStringAnyMap(schema["properties"]))
                required.addAll(toStringList(schema["required"]))
            }

            schema.containsKey("properties") -> {
                properties.putAll(toStringAnyMap(schema["properties"]))
                required.addAll(toStringList(schema["required"]))
            }

            else -> {
                properties.putAll(schema)
            }
        }

        parameters.properties = properties
        parameters.required = required
        return parameters
    }

    private fun toStringAnyMap(value: Any?): Map<String, Any> {
        val map = value as? Map<*, *> ?: return emptyMap()
        return map.entries.mapNotNull { (key, nestedValue) ->
            val strKey = key?.toString() ?: return@mapNotNull null
            nestedValue?.let { strKey to it }
        }.toMap()
    }

    private fun toStringList(value: Any?): List<String> {
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { it?.toString() }
    }
}
