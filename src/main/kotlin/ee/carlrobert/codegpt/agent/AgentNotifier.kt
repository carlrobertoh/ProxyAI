package ee.carlrobert.codegpt.agent

import com.intellij.util.messages.Topic

interface AgentToolOutputNotifier {
    fun toolOutput(toolId: String, text: String, isError: Boolean)

    companion object {
        val AGENT_TOOL_OUTPUT_TOPIC =
            Topic.create("agentToolOutput", AgentToolOutputNotifier::class.java)
    }
}
