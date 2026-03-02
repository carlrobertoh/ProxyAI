package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

object McpTool {

    @Serializable
    data class Args(
        @property:LLMDescription("Tool name to execute on the MCP server")
        @SerialName("tool_name")
        val toolName: String,
        @property:LLMDescription("Optional MCP server id when multiple servers expose the same tool")
        @SerialName("server_id")
        val serverId: String? = null,
        @property:LLMDescription("Optional MCP server name alternative to server_id")
        @SerialName("server_name")
        val serverName: String? = null,
        @property:LLMDescription("JSON object with tool arguments")
        val arguments: Map<String, JsonElement> = emptyMap()
    )

    @Serializable
    data class Result(
        @SerialName("server_id")
        val serverId: String?,
        @SerialName("server_name")
        val serverName: String?,
        @SerialName("tool_name")
        val toolName: String,
        val success: Boolean,
        val output: String
    ) {
        companion object {
            fun error(
                toolName: String,
                output: String,
                serverId: String? = null,
                serverName: String? = null
            ): Result {
                return Result(
                    serverId = serverId,
                    serverName = serverName,
                    toolName = toolName,
                    success = false,
                    output = output
                )
            }
        }
    }
}
