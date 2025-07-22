package ee.carlrobert.codegpt.services.llama

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.llama.LlamaConstants.MAX_LOG_ENTRIES
import ee.carlrobert.codegpt.completions.llama.LlamaConstants.MAX_LOG_SESSIONS
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Service
class ServerLogsManager {

    private val sessions = ConcurrentHashMap<String, LogSession>()
    private val sessionOrder = CopyOnWriteArrayList<String>()

    @Volatile
    private var currentSessionId: String? = null

    @Synchronized
    fun startNewSession(): LogSession {
        val sessionId = UUID.randomUUID().toString()
        val session = LogSession(sessionId, LocalDateTime.now())

        sessions[sessionId] = session
        sessionOrder.add(0, sessionId)
        currentSessionId = sessionId

        while (sessionOrder.size > MAX_LOG_SESSIONS) {
            val oldestId = sessionOrder.removeAt(sessionOrder.size - 1)
            sessions.remove(oldestId)
        }

        return session
    }

    fun getCurrentSession(): LogSession {
        val sessionId = currentSessionId
        return if (sessionId == null || !sessions.containsKey(sessionId)) {
            startNewSession()
        } else {
            sessions[sessionId]!!
        }
    }

    fun endCurrentSession() {
        currentSessionId?.let { id ->
            sessions[id]?.endTime = LocalDateTime.now()
        }
    }

    fun log(message: String, isError: Boolean) {
        val session = getCurrentSession()
        val contentType = if (isError) {
            ConsoleViewContentType.ERROR_OUTPUT
        } else {
            ConsoleViewContentType.NORMAL_OUTPUT
        }

        val entry = LogEntry(LocalDateTime.now(), message, contentType)
        session.entries.add(entry)

        while (session.entries.size > MAX_LOG_ENTRIES) {
            session.entries.removeAt(0)
        }
    }

    fun getAllSessions(): List<LogSession> {
        return sessionOrder.mapNotNull { id -> sessions[id] }
    }

    fun getSessionLogs(sessionId: String): List<LogEntry> {
        return sessions[sessionId]?.entries?.toList() ?: emptyList()
    }

    @Synchronized
    fun clearAll() {
        sessions.clear()
        sessionOrder.clear()
        currentSessionId = null
    }

    data class LogSession(
        val id: String,
        val startTime: LocalDateTime,
        var endTime: LocalDateTime? = null,
        val entries: MutableList<LogEntry> = CopyOnWriteArrayList()
    )

    data class LogEntry(
        val timestamp: LocalDateTime,
        val message: String,
        val contentType: ConsoleViewContentType
    )

    companion object {
        @JvmStatic
        fun getInstance(): ServerLogsManager = ApplicationManager.getApplication().service()
    }
}