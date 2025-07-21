package ee.carlrobert.codegpt.settings.service.google

import com.intellij.openapi.components.*
import ee.carlrobert.llm.client.google.models.GoogleModel

@Service
@State(name = "CodeGPT_GoogleSettings_210", storages = [Storage("CodeGPT_GoogleSettings_210.xml")])
class GoogleSettings : SimplePersistentStateComponent<GoogleSettingsState>(GoogleSettingsState())

class GoogleSettingsState : BaseState() {
    var model by string(GoogleModel.GEMINI_2_5_PRO.code)
}
