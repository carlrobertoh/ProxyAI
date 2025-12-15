package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.util.messages.Topic

interface AgentTabTitleNotifier {
    
    fun updateTabTitle(sessionId: String, title: String)
    
    companion object {
        @JvmStatic
        val AGENT_TAB_TITLE_TOPIC = Topic.create("agentTabTitle", AgentTabTitleNotifier::class.java)
    }
}