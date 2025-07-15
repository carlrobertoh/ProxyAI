package ee.carlrobert.codegpt.settings.service.ollama

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import ee.carlrobert.codegpt.codecompletions.InfillPromptTemplate

@State(name = "CodeGPT_OllamaSettings_210", storages = [Storage("CodeGPT_OllamaSettings_210.xml")])
class OllamaSettings :
    SimplePersistentStateComponent<OllamaSettingsState>(OllamaSettingsState())

class OllamaSettingsState : BaseState() {
    var host by string("http://localhost:11434")
    var model by string()
    var codeCompletionsEnabled by property(false)
    var fimOverride by property(true)
    var fimTemplate by enum<InfillPromptTemplate>(InfillPromptTemplate.CODE_QWEN_2_5)
    var availableModels by list<String>()
}