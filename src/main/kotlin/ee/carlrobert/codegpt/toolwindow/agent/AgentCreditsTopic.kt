package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.util.messages.Topic

data class AgentCreditsEvent(
    val sessionId: String,
    val remaining: Long,
    val monthlyRemaining: Long,
    val consumed: Long,
)

interface AgentCreditsListener {
    fun onCreditsChanged(event: AgentCreditsEvent)

    companion object {
        val AGENT_CREDITS_TOPIC = Topic("Agent Credits Changes", AgentCreditsListener::class.java)
    }
}
