package ee.carlrobert.codegpt.agent.external.acpcompat

import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.NewSessionRequest
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.transport.Transport
import ee.carlrobert.codegpt.agent.agentJson
import ee.carlrobert.codegpt.agent.external.acpcompat.vendor.AcpCompatibilityRegistry
import ee.carlrobert.codegpt.agent.external.acpcompat.vendor.AcpPeerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AcpProtocolJsonTest {
    private val compatibilityRegistry = AcpCompatibilityRegistry()

    @Test
    fun serializesMcpServerTypeInformation() {
        val protocol = AcpProtocol(
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transport = NoOpTransport()
        )

        val encoded = protocol.json.encodeToString(
            NewSessionRequest(
                cwd = "/tmp",
                mcpServers = listOf(
                    McpServer.Stdio(
                        name = "local",
                        command = "npx",
                        args = listOf("server"),
                        env = emptyList()
                    )
                )
            )
        )

        assertContains(encoded, "\"type\":\"stdio\"")
        assertContains(encoded, "\"mcpServers\"")
    }

    @Test
    fun normalizesEnvVarAuthMethodFromVarsArray() {
        val payload = agentJson.parseToJsonElement(
            """
            {
              "protocolVersion": 1,
              "agentCapabilities": {},
              "authMethods": [
                {
                  "type": "env_var",
                  "id": "openai-api-key",
                  "name": "Use OPENAI_API_KEY",
                  "vars": [
                    { "name": "OPENAI_API_KEY" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val normalized = compatibilityRegistry.normalizeInboundPayload(
            profile = AcpPeerProfile.CODEX,
            methodName = AcpMethod.AgentMethods.Initialize.methodName,
            payload = payload
        ).toString()

        assertContains(normalized, "\"varName\":\"OPENAI_API_KEY\"")
        assertEquals(1, Regex("\"varName\"").findAll(normalized).count())
    }
}

private class NoOpTransport : Transport {
    private val currentState = MutableStateFlow(Transport.State.CREATED)

    override val state: StateFlow<Transport.State> = currentState

    override fun start() {
        currentState.value = Transport.State.STARTED
    }

    override fun send(message: JsonRpcMessage) = Unit

    override fun onMessage(handler: (JsonRpcMessage) -> Unit) = Unit

    override fun onError(handler: (Throwable) -> Unit) = Unit

    override fun onClose(handler: () -> Unit) = Unit

    override fun close() {
        currentState.value = Transport.State.CLOSED
    }
}
