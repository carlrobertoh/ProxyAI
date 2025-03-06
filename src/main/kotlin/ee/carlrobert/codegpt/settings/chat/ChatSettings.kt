package ee.carlrobert.codegpt.settings.chat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service
@State(
    name = "ProxyAI_ChatSettings",
    storages = [Storage("ProxyAI_ChatSettings.xml")]
)
class ChatSettings :
    SimplePersistentStateComponent<ChatSettingsState>(ChatSettingsState()) {

    override fun loadState(state: ChatSettingsState) {
        super.loadState(state)
        ApplicationManager.getApplication().messageBus
            .syncPublisher(ChatSettingsListener.TOPIC)
            .onChatSettingsChanged(state)
    }
}

class ChatSettingsState : BaseState() {
    var editorContextTagEnabled by property(true)
    var psiStructureEnabled by property(true)
}
