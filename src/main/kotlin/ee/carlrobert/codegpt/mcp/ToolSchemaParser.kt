package ee.carlrobert.codegpt.mcp

import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType

object ToolSchemaParser {

    fun parseRequiredNames(schema: Map<String, Any?>): List<String> {
        return (schema["required"] as? List<*>)
            ?.mapNotNull { it?.toString() }
            ?: emptyList()
    }

    fun parseParameterDescriptors(schema: Map<String, Any?>): List<ToolParameterDescriptor> {
        val properties = schema["properties"] as? Map<*, *> ?: emptyMap<Any, Any?>()
        return properties.mapNotNull { (nameRaw, propertyRaw) ->
            val name = nameRaw?.toString() ?: return@mapNotNull null
            val property = propertyRaw as? Map<*, *> ?: return@mapNotNull null
            toToolParameterDescriptor(name, property)
        }
    }

    private fun parseParameterType(definition: Map<*, *>): ToolParameterType {
        val enumValues = (definition["enum"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        if (enumValues.isNotEmpty()) {
            return ToolParameterType.Enum(enumValues.toTypedArray())
        }

        val anyOf = definition["anyOf"] as? List<*>
        if (!anyOf.isNullOrEmpty()) {
            val mapped = anyOf.mapNotNull { it as? Map<*, *> }
            if (mapped.size == 2 && mapped.any { it["type"]?.toString()?.lowercase() == "null" }) {
                return parseParameterType(mapped.first {
                    it["type"]?.toString()?.lowercase() != "null"
                })
            }
            val types = mapped.map { element ->
                toToolParameterDescriptor("", element)
            }.toTypedArray()
            return ToolParameterType.AnyOf(types)
        }

        return when (definition["type"]?.toString()?.lowercase()) {
            "string" -> ToolParameterType.String
            "integer" -> ToolParameterType.Integer
            "number" -> ToolParameterType.Float
            "boolean" -> ToolParameterType.Boolean
            "null" -> ToolParameterType.Null
            "array" -> {
                val itemDefinition = definition["items"] as? Map<*, *> ?: emptyMap<Any, Any?>()
                ToolParameterType.List(parseParameterType(itemDefinition))
            }

            "object" -> {
                val properties = parseParameterDescriptors(toStringKeyedMap(definition["properties"]))
                val required = (definition["required"] as? List<*>)
                    ?.mapNotNull { it?.toString() }
                    ?: emptyList()
                ToolParameterType.Object(
                    properties = properties,
                    requiredProperties = required
                )
            }

            else -> ToolParameterType.String
        }
    }

    private fun toToolParameterDescriptor(
        name: String,
        definition: Map<*, *>
    ): ToolParameterDescriptor {
        return ToolParameterDescriptor(
            name = name,
            description = definition["description"]?.toString().orEmpty(),
            type = parseParameterType(definition)
        )
    }

    private fun toStringKeyedMap(value: Any?): Map<String, Any?> {
        val map = value as? Map<*, *> ?: return emptyMap()
        return map.entries.mapNotNull { (key, nestedValue) ->
            key?.toString()?.let { it to nestedValue }
        }.toMap()
    }
}
