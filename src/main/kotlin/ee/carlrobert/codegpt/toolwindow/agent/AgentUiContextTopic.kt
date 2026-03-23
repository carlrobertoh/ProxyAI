package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.util.messages.Topic

interface AgentUiContextListener {
    fun onUiContextChanged()

    companion object {
        val AGENT_UI_CONTEXT_TOPIC = Topic("Agent UI Context Changes", AgentUiContextListener::class.java)
    }
}
