package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.AgentMcpContext
import ee.carlrobert.codegpt.mcp.ConnectionStatus
import ee.carlrobert.codegpt.mcp.McpSessionAttachment
import ee.carlrobert.codegpt.mcp.McpSessionManager
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.serialization.json.JsonObject
import java.util.*
import ee.carlrobert.codegpt.mcp.McpTool as SessionMcpTool

object McpDynamicToolRegistry {
    private val logger = thisLogger()

    fun createTools(
        context: AgentMcpContext,
        approve: suspend (name: String, details: String) -> Boolean
    ): List<Tool<*, *>> {
        val conversationId = context.conversationId ?: return emptyList()
        if (!context.hasSelection()) return emptyList()

        val attachments = context.selectedServerIds.mapNotNull { serverId ->
            ensureConnectedAttachment(conversationId, serverId)
        }.filter { it.availableTools.isNotEmpty() }

        if (attachments.isEmpty()) return emptyList()

        val namesByTool = attachments
            .flatMap { attachment -> attachment.availableTools.map { it.name.lowercase() } }
            .groupingBy { it }
            .eachCount()

        val usedNames = mutableSetOf<String>()
        val tools = mutableListOf<Tool<*, *>>()
        attachments.forEach { attachment ->
            attachment.availableTools.forEach { tool ->
                val exposedName = uniqueToolName(
                    toolName = tool.name,
                    attachment = attachment,
                    hasCollisions = (namesByTool[tool.name.lowercase()] ?: 0) > 1,
                    usedNames = usedNames
                )
                tools.add(
                    SessionBoundMcpTool(
                        conversationId = conversationId,
                        serverId = attachment.serverId,
                        serverName = attachment.serverName,
                        sourceTool = tool,
                        exposedName = exposedName,
                        approve = approve
                    )
                )
            }
        }
        return tools
    }

    private fun uniqueToolName(
        toolName: String,
        attachment: McpSessionAttachment,
        hasCollisions: Boolean,
        usedNames: MutableSet<String>
    ): String {
        val base = if (hasCollisions) {
            "${toolName}_${normalizeName(attachment.serverName.ifBlank { attachment.serverId })}"
        } else {
            toolName
        }
        if (usedNames.add(base)) return base
        var index = 2
        while (true) {
            val candidate = "${base}_$index"
            if (usedNames.add(candidate)) return candidate
            index++
        }
    }

    private fun normalizeName(value: String): String {
        val normalized = value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return normalized.ifBlank { "server" }
    }

    private fun ensureConnectedAttachment(
        conversationId: UUID,
        serverId: String
    ): McpSessionAttachment? {
        val sessionManager = service<McpSessionManager>()
        val existing = runCatching {
            sessionManager.getSessionAttachments(conversationId)
                .firstOrNull { it.serverId == serverId }
        }.getOrNull()
        if (existing != null && existing.connectionStatus == ConnectionStatus.CONNECTED) {
            return existing
        }
        val attachment = runCatching {
            sessionManager.attachServerToSession(conversationId, serverId).get()
        }.onFailure { error ->
            logger.warn("Failed to attach MCP server '$serverId': ${error.message}")
        }.getOrNull()
        if (attachment?.connectionStatus != ConnectionStatus.CONNECTED) {
            return null
        }
        return attachment
    }
}

internal interface McpAgentToolMarker {
    fun toDisplayArgs(args: JsonObject): McpTool.Args
}

private class SessionBoundMcpTool(
    private val conversationId: UUID,
    private val serverId: String,
    private val serverName: String,
    private val sourceTool: SessionMcpTool,
    private val exposedName: String,
    private val approve: suspend (name: String, details: String) -> Boolean
) : Tool<JsonObject, McpTool.Result>(
    argsSerializer = JsonObject.serializer(),
    resultSerializer = McpTool.Result.serializer(),
    descriptor = buildDescriptor(exposedName, sourceTool, serverName)
), McpAgentToolMarker {
    override suspend fun execute(args: JsonObject): McpTool.Result {
        val details = buildMcpApprovalDetails(serverId, serverName, sourceTool.name, args)
        val approved = runCatching { approve(exposedName, details) }.getOrDefault(false)
        if (!approved) {
            return errorResult("User rejected MCP tool execution")
        }

        val sessionManager = service<McpSessionManager>()
        val attachment = runCatching {
            sessionManager.attachServerToSession(conversationId, serverId).get()
        }.getOrElse { error ->
            return errorResult(
                "Failed to attach MCP server '$serverName': ${error.message ?: "unknown error"}"
            )
        }
        if (attachment.connectionStatus != ConnectionStatus.CONNECTED) {
            return errorResult("MCP server '$serverName' is not connected")
        }

        val client = runCatching {
            val clientKey = "$conversationId:$serverId"
            sessionManager.ensureClientConnected(clientKey).get()
        }.getOrNull()
            ?: return errorResult("MCP server '$serverName' is not connected")

        return runTool(client, args)
    }

    override fun encodeResultToString(result: McpTool.Result): String {
        return if (result.success || result.output.startsWith("Error:", ignoreCase = true)) {
            result.output
        } else {
            "Error: ${result.output}"
        }
    }

    override fun toDisplayArgs(args: JsonObject): McpTool.Args {
        return McpTool.Args(
            toolName = sourceTool.name,
            serverId = serverId,
            serverName = serverName,
            arguments = args
        )
    }

    private fun runTool(client: McpSyncClient, args: JsonObject): McpTool.Result {
        return runCatching {
            val callArgs = args.toMcpArguments()
            val request = McpSchema.CallToolRequest(sourceTool.name, callArgs)
            val result = client.callTool(request)
            val content = result.formatMcpContent()
            if (result.isError == true) {
                errorResult("Tool execution failed: $content")
            } else {
                successResult(content)
            }
        }.getOrElse { error ->
            errorResult(error.message ?: "MCP tool execution failed")
        }
    }

    private fun successResult(output: String): McpTool.Result {
        return McpTool.Result(
            serverId = serverId,
            serverName = serverName,
            toolName = sourceTool.name,
            success = true,
            output = output
        )
    }

    private fun errorResult(output: String): McpTool.Result {
        return McpTool.Result.error(
            toolName = sourceTool.name,
            output = output,
            serverId = serverId,
            serverName = serverName
        )
    }
}

private fun buildDescriptor(
    exposedName: String,
    sourceTool: SessionMcpTool,
    serverName: String
): ToolDescriptor {
    val description = buildString {
        append(sourceTool.description.ifBlank { "MCP tool" })
        append(" (server: ")
        append(serverName)
        append(')')
    }
    val allParameters = parseParameterDescriptors(sourceTool.schema)
    val requiredNames = parseRequiredNames(sourceTool.schema).toSet()
    val required = allParameters.filter { it.name in requiredNames }
    val optional = allParameters.filterNot { it.name in requiredNames }
    return ToolDescriptor(
        name = exposedName,
        description = description,
        requiredParameters = required,
        optionalParameters = optional
    )
}

private fun parseRequiredNames(schema: Map<String, Any?>): List<String> {
    return (schema["required"] as? List<*>)
        ?.mapNotNull { it?.toString() }
        ?: emptyList()
}

private fun parseParameterDescriptors(schema: Map<String, Any?>): List<ToolParameterDescriptor> {
    val properties = schema["properties"] as? Map<*, *> ?: emptyMap<Any, Any?>()
    return properties.mapNotNull { (nameRaw, propertyRaw) ->
        val name = nameRaw?.toString() ?: return@mapNotNull null
        val property = propertyRaw as? Map<*, *> ?: emptyMap<Any, Any?>()
        ToolParameterDescriptor(
            name = name,
            description = property["description"]?.toString().orEmpty(),
            type = parseParameterType(property)
        )
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
            ToolParameterDescriptor(
                name = "",
                description = element["description"]?.toString().orEmpty(),
                type = parseParameterType(element)
            )
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

private fun toStringKeyedMap(value: Any?): Map<String, Any?> {
    val map = value as? Map<*, *> ?: return emptyMap()
    return map.entries.mapNotNull { (key, nestedValue) ->
        key?.toString()?.let { it to nestedValue }
    }.toMap()
}
