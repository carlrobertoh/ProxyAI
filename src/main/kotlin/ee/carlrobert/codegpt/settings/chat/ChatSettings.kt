package ee.carlrobert.codegpt.settings.chat

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service
@State(
    name = "ProxyAI_ChatSettings",
    storages = [Storage("ProxyAI_ChatSettings.xml")]
)
class ChatSettings :
    SimplePersistentStateComponent<ChatSettingsState>(ChatSettingsState()) {
    companion object {
        @JvmStatic
        fun getState(): ChatSettingsState {
            return service<ChatSettings>().state
        }
    }
}

class ChatSettingsState : BaseState() {
    var editorContextTagEnabled by property(true)
    var psiStructureEnabled by property(true)
}
