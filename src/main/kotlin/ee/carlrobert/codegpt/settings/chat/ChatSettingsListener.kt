package ee.carlrobert.codegpt.settings.chat

import com.intellij.util.messages.Topic

fun interface ChatSettingsListener {
    fun onChatSettingsChanged(newState: ChatSettingsState)
    
    companion object {
        val TOPIC = Topic.create("Chat Settings Changed", ChatSettingsListener::class.java)
    }
}