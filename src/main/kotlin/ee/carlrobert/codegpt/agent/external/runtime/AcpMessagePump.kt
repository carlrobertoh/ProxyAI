package ee.carlrobert.codegpt.agent.external.runtime

import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.transport.Transport
import com.agentclientprotocol.transport.asMessageChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val pumpLogger = KotlinLogging.logger {}

internal class AcpMessagePump(
    private val scope: CoroutineScope,
    private val transport: Transport,
    private val protocolDebugName: String,
    private val onMessage: suspend (JsonRpcMessage) -> Unit
) {

    fun start() {
        scope.launch(CoroutineName("$protocolDebugName.read-messages")) {
            try {
                for (message in transport.asMessageChannel()) {
                    try {
                        onMessage(message)
                    } catch (e: Exception) {
                        pumpLogger.error(e) { "Error processing incoming message: $message" }
                    }
                }
            } catch (e: Exception) {
                pumpLogger.error(e) { "Error processing incoming messages" }
            }
        }
        transport.start()
    }
}
