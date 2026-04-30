package ee.carlrobert.codegpt.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.settings.mcp.McpServerDetailsState
import ee.carlrobert.codegpt.settings.mcp.McpSettings
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class McpSessionManager {

    companion object {
        private val logger = thisLogger()
    }

    private val attachmentsByConversation =
        ConcurrentHashMap<String, ConcurrentHashMap<String, McpSessionAttachment>>()
    private val activeClients = ConcurrentHashMap<String, McpSyncClient>()
    private val mcpSettings = service<McpSettings>()

    fun attachServerToSession(
        conversationId: UUID,
        serverId: String
    ): CompletableFuture<McpSessionAttachment> {
        return CompletableFuture.supplyAsync {
            try {
                val serverDetails = getServerDetails(serverId)
                    ?: throw IllegalArgumentException("Server with ID $serverId not found")

                val command = serverDetails.command ?: "npx"
                val resolvedCommand = McpCommandValidator.resolveCommand(
                    command = command,
                    extraEnvironment = serverDetails.environmentVariables
                )
                if (resolvedCommand == null) {
                    val errorMsg = McpCommandValidator.getCommandNotFoundMessage(command)
                    logger.error(
                        "MCP command not found for server '${serverDetails.name}' (id=$serverId): $command"
                    )
                    throw IllegalArgumentException("Failed to resolve command: $errorMsg")
                }

                val mergedEnv =
                    McpPathHelper.createEnvironment(
                        serverDetails.environmentVariables,
                        resolvedCommand
                    )

                val serverParameters = ServerParameters.builder(resolvedCommand)
                    .args(*serverDetails.arguments.toTypedArray())
                    .env(mergedEnv)
                    .build()

                val client = initializeSession(serverParameters)
                val clientKey = "${conversationId}:${serverId}"
                activeClients[clientKey] = client

                val tools = discoverTools(client, serverId)
                val resources = discoverResources(client, serverId)

                val attachment = McpSessionAttachment(
                    serverId = serverId,
                    serverName = serverDetails.name ?: "Unknown Server",
                    connectionStatus = ConnectionStatus.CONNECTED,
                    availableTools = tools,
                    availableResources = resources,
                    attachedAt = System.currentTimeMillis()
                )

                val convAttachments = getConversationAttachments(conversationId)
                convAttachments[serverId] = attachment
                attachment
            } catch (e: Exception) {
                val serverDetails = getServerDetails(serverId)
                val attachment = McpSessionAttachment(
                    serverId = serverId,
                    serverName = serverDetails?.name ?: "Unknown Server",
                    connectionStatus = ConnectionStatus.ERROR,
                    availableTools = emptyList(),
                    availableResources = emptyList(),
                    attachedAt = System.currentTimeMillis(),
                    lastError = e.message
                )
                val convAttachments = getConversationAttachments(conversationId)
                convAttachments[serverId] = attachment
                logger.error(
                    "Failed to attach MCP server '${serverDetails?.name ?: serverId}' (id=$serverId): ${e.message}"
                )
                attachment
            }
        }
    }

    fun getSessionAttachments(conversationId: UUID): List<McpSessionAttachment> {
        return getConversationAttachments(conversationId).values.toList()
    }

    fun getServerInfo(conversationId: UUID, serverId: String): String? {
        return try {
            val attachment = getSessionAttachment(conversationId, serverId)
            attachment?.serverName ?: getServerName(serverId)
        } catch (e: Exception) {
            logger.warn("Error getting server info for '$serverId'", e)
            null
        }
    }

    fun ensureClientConnected(clientKey: String): CompletableFuture<McpSyncClient?> {
        return CompletableFuture.supplyAsync {
            val client = activeClients[clientKey]
            if (client != null) {
                return@supplyAsync client
            }

            val parts = clientKey.split(":")
            if (parts.size != 2) {
                logger.warn("Invalid client key format: '$clientKey'")
                return@supplyAsync null
            }

            val conversationId = parts[0]
            val serverId = parts[1]

            val attachment = attachmentsByConversation[conversationId]?.get(serverId)

            if (attachment != null && attachment.isConnected()) {
                try {
                    logger.info("Reconnecting MCP client for key '$clientKey'")
                    val reconnectFuture = reconnectServer(UUID.fromString(conversationId), serverId)
                    reconnectFuture.get()
                    return@supplyAsync activeClients[clientKey]
                } catch (e: Exception) {
                    logger.error("Failed to reconnect MCP client for key '$clientKey'", e)
                }
            }

            null
        }
    }

    private fun reconnectServer(
        conversationId: UUID,
        serverId: String
    ): CompletableFuture<McpSessionAttachment> {
        return detachServerFromSession(conversationId, serverId)
            .thenCompose { attachServerToSession(conversationId, serverId) }
    }

    private fun getSessionAttachment(
        conversationId: UUID,
        serverId: String,
    ): McpSessionAttachment? {
        return try {
            getSessionAttachments(conversationId).find { it.serverId == serverId }
        } catch (e: Exception) {
            logger.warn("Error getting session attachment for server '$serverId'", e)
            null
        }
    }

    private fun initializeSession(connectionParams: ServerParameters): McpSyncClient {
        val transport = StdioClientTransport(connectionParams)
        val client = McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(30))
            .capabilities(ClientCapabilities.builder().build())
            .build()

        client.initialize()
        return client
    }

    private fun detachServerFromSession(
        conversationId: UUID,
        serverId: String
    ): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            try {
                val clientKey = "${conversationId}:${serverId}"
                activeClients[clientKey]?.let { client ->
                    try {
                        client.close()
                    } catch (e: Exception) {
                        logger.warn("Error closing MCP client for server '$serverId'", e)
                    }
                    activeClients.remove(clientKey)
                }

                val convAttachments = getConversationAttachments(conversationId)
                convAttachments.remove(serverId)
                logger.info("Detached MCP server '$serverId' from conversation '$conversationId'")
            } catch (e: Exception) {
                logger.error(
                    "Failed to detach MCP server '$serverId' from conversation '$conversationId'",
                    e
                )
                throw e
            }
        }
    }

    private fun discoverTools(client: McpSyncClient, serverId: String): List<McpTool> {
        return try {
            val toolsResult = client.listTools()

            toolsResult.tools.map { tool ->
                McpTool(
                    name = tool.name,
                    description = tool.description ?: "",
                    serverId = serverId,
                    schema = tool.inputSchema?.let {
                        mutableMapOf(
                            "type" to "object",
                            "properties" to it.properties,
                            "required" to (it.required ?: emptyList())
                        )
                    } ?: mutableMapOf()
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to discover tools for server '$serverId'", e)
            emptyList()
        }
    }

    private fun discoverResources(client: McpSyncClient, serverId: String): List<McpResource> {
        return try {
            val resourcesResult = client.listResources()
            resourcesResult.resources.map { resource ->
                McpResource(
                    uri = resource.uri,
                    name = resource.name,
                    description = resource.description,
                    serverId = serverId,
                    mimeType = resource.mimeType
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to discover resources for server '$serverId'", e)
            emptyList()
        }
    }

    private fun getConversationAttachments(conversationId: UUID): ConcurrentHashMap<String, McpSessionAttachment> {
        return attachmentsByConversation.computeIfAbsent(conversationId.toString()) { ConcurrentHashMap() }
    }

    private fun getServerDetails(serverId: String): McpServerDetailsState? {
        return try {
            mcpSettings.state.servers.find { it.id.toString() == serverId }
        } catch (_: Exception) {
            null
        }
    }

    private fun getServerName(serverId: String): String {
        return getServerDetails(serverId)?.name ?: "Unknown Server"
    }
}
