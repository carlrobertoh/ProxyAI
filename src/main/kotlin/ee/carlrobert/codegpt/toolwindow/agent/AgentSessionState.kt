package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "ProxyAI_AgentSessionState",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
@Service(Service.Level.PROJECT)
class AgentSessionState : PersistentStateComponent<AgentSessionState.State> {

    data class SessionEntry(
        var sessionId: String = "",
        var lastAgentId: String? = null,
        var checkpointId: String? = null,
        var displayName: String = "",
    )

    data class State(
        var sessions: MutableList<SessionEntry> = mutableListOf(),
        var lastActiveSessionId: String? = null
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun getSessionIds(): List<String> {
        return state.sessions.mapNotNull { it.sessionId.takeIf { id -> id.isNotBlank() } }
    }

    fun getLastActiveSessionId(): String? = state.lastActiveSessionId

    fun setLastActiveSessionId(sessionId: String) {
        state.lastActiveSessionId = sessionId
    }

    fun getLastAgentId(sessionId: String): String? {
        return state.sessions.firstOrNull { it.sessionId == sessionId }?.lastAgentId
    }

    fun getDisplayName(sessionId: String): String? {
        return state.sessions.firstOrNull { it.sessionId == sessionId }?.displayName
    }

    fun ensureSession(sessionId: String): SessionEntry {
        val existing = state.sessions.firstOrNull { it.sessionId == sessionId }
        if (existing != null) return existing

        val entry = SessionEntry(sessionId = sessionId)
        state.sessions.add(entry)
        return entry
    }

    fun updateSession(sessionId: String, lastAgentId: String? = null, checkpointId: String? = null, displayName: String? = null) {
        val entry = ensureSession(sessionId)
        if (lastAgentId != null) {
            entry.lastAgentId = lastAgentId
        }
        if (displayName != null) {
            entry.displayName = displayName
        }
        if (checkpointId != null) {
            entry.checkpointId = checkpointId
        }
    }

    fun removeSession(sessionId: String) {
        state.sessions.removeIf { it.sessionId == sessionId }
        if (state.lastActiveSessionId == sessionId) {
            state.lastActiveSessionId = null
        }
    }

    fun replaceSession(oldSessionId: String, newSessionId: String) {
        removeSession(oldSessionId)
        ensureSession(newSessionId)
        state.lastActiveSessionId = newSessionId
    }
}
