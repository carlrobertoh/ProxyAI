package ee.carlrobert.codegpt.settings.service.inception

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "CodeGPT_InceptionSettings", storages = [Storage("CodeGPT_InceptionSettings.xml")])
class InceptionSettings : SimplePersistentStateComponent<InceptionSettingsState>(InceptionSettingsState())

class InceptionSettingsState : BaseState() {
    var codeCompletionsEnabled by property(true)
    var nextEditsEnabled by property(true)
}

