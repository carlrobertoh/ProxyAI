package ee.carlrobert.codegpt.settings.agents.acp

import com.intellij.openapi.components.*
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgentPreset
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgents

@Service(Service.Level.PROJECT)
@State(
    name = "CodeGPT_AcpAgentSettings",
    storages = [Storage("CodeGPT_AcpAgentSettings.xml")]
)
class AcpAgentSettings :
    SimplePersistentStateComponent<AcpAgentSettingsState>(
        AcpAgentSettingsState().apply {
            enabledAgentIds = ExternalAcpAgents.enabledByDefaultIds().toMutableList()
        }
    ) {

    fun getEnabledPresetIds(): List<String> = state.enabledAgentIds

    fun getVisiblePresets(currentPresetId: String? = null): List<ExternalAcpAgentPreset> {
        val visibleIds = state.enabledAgentIds.toMutableSet()
        currentPresetId?.takeIf { it.isNotBlank() }?.let(visibleIds::add)
        return ExternalAcpAgents.all().filter { it.id in visibleIds }
    }

    fun setEnabledPresetIds(ids: Collection<String>) {
        state.enabledAgentIds = ids.distinct().toMutableList()
    }
}

class AcpAgentSettingsState : BaseState() {
    var enabledAgentIds by list<String>()
}
