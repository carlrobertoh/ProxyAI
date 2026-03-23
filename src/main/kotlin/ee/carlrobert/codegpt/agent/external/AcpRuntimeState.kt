package ee.carlrobert.codegpt.agent.external

import com.agentclientprotocol.model.AcpCreatedSessionResponse
import com.agentclientprotocol.model.AvailableCommand
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionModelState
import com.agentclientprotocol.model.SessionModeState
import kotlinx.serialization.json.JsonElement

internal data class AcpRuntimeState(
    val modes: SessionModeState? = null,
    val models: SessionModelState? = null,
    val configOptions: List<SessionConfigOption> = emptyList(),
    val availableCommands: List<AvailableCommand> = emptyList(),
    val sessionTitle: String? = null,
    val sessionUpdatedAt: String? = null,
    val vendorMeta: JsonElement? = null
)

internal fun AcpCreatedSessionResponse.toRuntimeState(): AcpRuntimeState {
    return AcpRuntimeState(
        modes = modes,
        models = models,
        configOptions = configOptions.orEmpty(),
        vendorMeta = _meta
    )
}
