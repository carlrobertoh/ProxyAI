package ee.carlrobert.codegpt.agent

import java.util.concurrent.ConcurrentHashMap

data class ToolContext(
    val sessionId: String,
    val toolId: String,
    val checkpointId: String? = null,
    val parentToolId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object ToolRunContext {
    private val sessionContexts = ConcurrentHashMap<String, ThreadLocal<ToolContext?>>()

    fun set(sessionId: String, toolId: String, checkpointId: String? = null) {
        val currentContext = get(sessionId)
        val context = ToolContext(
            sessionId = sessionId,
            toolId = toolId,
            checkpointId = checkpointId,
            parentToolId = currentContext?.toolId,
            timestamp = System.currentTimeMillis()
        )
        sessionContexts.computeIfAbsent(sessionId) { ThreadLocal() }.set(context)
    }

    fun get(sessionId: String): ToolContext? {
        return sessionContexts[sessionId]?.get()
    }

    fun getToolId(sessionId: String): String? {
        return sessionContexts[sessionId]?.get()?.toolId
    }

    fun clear(sessionId: String) {
        sessionContexts[sessionId]?.remove()
    }

    fun cleanupSession(sessionId: String) {
        sessionContexts.remove(sessionId)
    }
}

