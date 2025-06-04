package ee.carlrobert.codegpt.settings.service.codegpt

import com.intellij.openapi.components.*

@Service
@State(
    name = "CodeGPT_CodeGPTServiceSettings_280",
    storages = [Storage("CodeGPT_CodeGPTServiceSettings_280.xml")]
)
class CodeGPTServiceSettings :
    SimplePersistentStateComponent<CodeGPTServiceSettingsState>(CodeGPTServiceSettingsState())

class CodeGPTServiceSettingsState : BaseState() {
    var chatCompletionSettings by property(CodeGPTServiceChatCompletionSettingsState())
    var codeCompletionSettings by property(CodeGPTServiceCodeCompletionSettingsState())
    var nextEditsEnabled by property(true)
}

class CodeGPTServiceChatCompletionSettingsState : BaseState() {
    var model by string("gpt-4.1-mini")
}

class CodeGPTServiceCodeCompletionSettingsState : BaseState() {
    var codeCompletionsEnabled by property(true)
    var model by string(CodeGPTAvailableModels.DEFAULT_CODE_MODEL.code)
}
