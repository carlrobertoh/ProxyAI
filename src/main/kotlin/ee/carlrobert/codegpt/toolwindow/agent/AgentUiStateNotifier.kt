package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.util.messages.Topic

interface AgentUiStateNotifier {

    fun activeSessionChanged()

    fun sessionRuntimeChanged(sessionId: String)

    companion object {
        @JvmField
        val AGENT_UI_STATE_TOPIC: Topic<AgentUiStateNotifier> =
            Topic.create("agentUiState", AgentUiStateNotifier::class.java)
    }
}
