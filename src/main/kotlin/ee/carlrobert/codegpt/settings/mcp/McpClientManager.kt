package ee.carlrobert.codegpt.settings.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.mcp.McpCommandValidator
import ee.carlrobert.codegpt.mcp.McpPathHelper
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import java.time.Duration
import java.util.concurrent.TimeoutException

@Service
class McpClientManager {

    private val logger = thisLogger()

    fun createClient(serverDetails: McpServerDetailsState): McpSyncClient? {
        return try {
            val command = serverDetails.command ?: "npx"
            val resolvedCommand = McpCommandValidator.resolveCommand(
                command = command,
                extraEnvironment = serverDetails.environmentVariables
            )
                ?: throw IllegalStateException(McpCommandValidator.getCommandNotFoundMessage(command))

            val enhancedEnv =
                McpPathHelper.createEnvironment(serverDetails.environmentVariables, resolvedCommand)
            val connectionParams = ServerParameters.builder(resolvedCommand)
                .args(*serverDetails.arguments.toTypedArray())
                .env(enhancedEnv)
                .build()

            McpClient.sync(StdioClientTransport(connectionParams))
                .requestTimeout(Duration.ofSeconds(30))
                .loggingConsumer { notification ->
                    logger.info("MCP Server '${serverDetails.name}': ${notification.data()}")
                }
                .build()
        } catch (e: Exception) {
            logger.warn("Failed to create MCP client for server '${serverDetails.name}'", e)
            null
        }
    }

    data class ToolInfo(val name: String, val description: String)

    data class ConnectionTestResult(
        val success: Boolean,
        val serverName: String? = null,
        val serverVersion: String? = null,
        val tools: List<ToolInfo> = emptyList(),
        val toolDescriptions: Map<String, String> = emptyMap(),
        val resources: List<String> = emptyList(),
        val errorMessage: String? = null
    )

    fun testConnection(serverDetails: McpServerDetailsState): ConnectionTestResult {
        val startTime = System.currentTimeMillis()

        return try {
            val command = serverDetails.command ?: "npx"
            val resolvedCommand = McpCommandValidator.resolveCommand(
                command = command,
                extraEnvironment = serverDetails.environmentVariables
            )
            if (resolvedCommand == null) {
                logger.warn("Command not found for '${serverDetails.name}': $command")
                return ConnectionTestResult(
                    success = false,
                    errorMessage = McpCommandValidator.getCommandNotFoundMessage(command)
                )
            }

            val client = createClient(serverDetails)
            if (client == null) {
                logger.warn("Failed to create client for '${serverDetails.name}'")
                return ConnectionTestResult(
                    success = false,
                    errorMessage = "Failed to create client - check command and arguments"
                )
            }

            client.use { mcpClient ->
                logger.info("Client created, attempting to initialize connection...")

                val initResult = mcpClient.initialize()
                val serverInfo = initResult.serverInfo
                logger.info("Connection initialized successfully")

                val toolsResult = try {
                    mcpClient.listTools()
                } catch (e: Exception) {
                    logger.debug("Failed to list tools: ${e.message}")
                    null
                }

                val resourcesResult = try {
                    mcpClient.listResources()
                } catch (e: Exception) {
                    logger.debug("Failed to list resources: ${e.message}")
                    null
                }

                val tools = toolsResult?.tools?.map {
                    ToolInfo(it.name, it.description ?: "No description available")
                } ?: emptyList()
                val toolDescriptions = toolsResult?.tools?.associate {
                    it.name to (it.description ?: "No description available")
                } ?: emptyMap()
                val resources = resourcesResult?.resources?.map { it.name } ?: emptyList()

                val duration = System.currentTimeMillis() - startTime
                logger.info("Connection test successful for '${serverDetails.name}': ${serverInfo.name} (took ${duration}ms)")

                ConnectionTestResult(
                    success = true,
                    serverName = serverInfo.name,
                    serverVersion = serverInfo.version,
                    tools = tools,
                    toolDescriptions = toolDescriptions,
                    resources = resources
                )
            }
        } catch (e: TimeoutException) {
            val duration = System.currentTimeMillis() - startTime
            logger.warn(
                "Connection test timed out for '${serverDetails.name}' after ${duration}ms",
                e
            )
            ConnectionTestResult(
                success = false,
                errorMessage = "Connection timed out after ${duration / 1000} seconds. The server may be unavailable or slow to respond."
            )
        } catch (e: java.io.IOException) {
            val duration = System.currentTimeMillis() - startTime
            logger.warn(
                "IO error during connection test for '${serverDetails.name}' after ${duration}ms",
                e
            )
            ConnectionTestResult(
                success = false,
                errorMessage = "Network error: ${e.message}. Check if the server command is correct and accessible."
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.warn("Connection test failed for '${serverDetails.name}' after ${duration}ms", e)
            ConnectionTestResult(
                success = false,
                errorMessage = when {
                    e.message?.contains("No such file") == true -> "Command not found: '${serverDetails.command}'. Make sure it's installed and in PATH."
                    e.message?.contains("Permission denied") == true -> "Permission denied. Check file permissions and execution rights."
                    e.message?.contains("Connection refused") == true -> "Connection refused. The server may not be running or accessible."
                    e.message?.contains("timeout") == true || e.message?.contains("timed out") == true -> "Connection timed out. The server may be slow to respond or unavailable."
                    else -> e.message ?: "Unknown connection error"
                }
            )
        }
    }
}
