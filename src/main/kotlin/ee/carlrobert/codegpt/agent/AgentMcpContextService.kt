package ee.carlrobert.codegpt.agent

import com.intellij.openapi.components.Service
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class AgentMcpContext(
    val conversationId: UUID?,
    val selectedServerIds: Set<String>
) {
    fun hasSelection(): Boolean = selectedServerIds.isNotEmpty()
}

@Service(Service.Level.PROJECT)
class AgentMcpContextService {
    private val contexts = ConcurrentHashMap<String, AgentMcpContext>()

    fun update(sessionId: String, conversationId: UUID?, selectedServerIds: Set<String>) {
        contexts[sessionId] = AgentMcpContext(conversationId, selectedServerIds)
    }

    fun get(sessionId: String): AgentMcpContext? = contexts[sessionId]

    fun clear(sessionId: String) {
        contexts.remove(sessionId)
    }
}
