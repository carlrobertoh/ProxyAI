package ee.carlrobert.codegpt.settings.configuration

import com.intellij.openapi.components.*
import ee.carlrobert.codegpt.actions.editor.EditorActionsUtil
import ee.carlrobert.codegpt.settings.prompts.CoreActionsState
import kotlin.math.max
import kotlin.math.min

@Service
@State(
    name = "CodeGPT_ConfigurationSettings_210",
    storages = [Storage("CodeGPT_ConfigurationSettings_210.xml")]
)
class ConfigurationSettings :
    SimplePersistentStateComponent<ConfigurationSettingsState>(ConfigurationSettingsState()) {
    companion object {
        @JvmStatic
        fun getState(): ConfigurationSettingsState {
            return service<ConfigurationSettings>().state
        }
    }
}

class ConfigurationSettingsState : BaseState() {
    var debugModeEnabled by property(false)
    var commitMessagePrompt by string(CoreActionsState.DEFAULT_GENERATE_COMMIT_MESSAGE_PROMPT)
    var maxTokens by property(8192)
    var temperature by property(0.1f) { max(0f, min(1f, it)) }
    var checkForPluginUpdates by property(true)
    var checkForNewScreenshots by property(true)
    var screenshotWatchPaths by list<String>()
    var ignoreGitCommitTokenLimit by property(false)
    var methodNameGenerationEnabled by property(true)
    var captureCompileErrors by property(true)
    var autoFormattingEnabled by property(true)
    var tableData by map<String, String>()
    var chatCompletionSettings by property(ChatCompletionSettingsState())
    var codeCompletionSettings by property(CodeCompletionSettingsState())

    init {
        tableData.putAll(EditorActionsUtil.DEFAULT_ACTIONS)

        if (screenshotWatchPaths.isEmpty()) {
            screenshotWatchPaths.addAll(ScreenshotPathDetector.getDefaultPaths())
        }
    }
}

class ChatCompletionSettingsState : BaseState() {
    var editorContextTagEnabled by property(true)
    var psiStructureEnabled by property(true)
    var psiStructureAnalyzeDepth by property(3)
    var clickableLinksEnabled by property(true)
    var sendWithAltEnter by property(false)
    var sendWithCtrlEnter by property(false)
    var sendWithShiftEnter by property(false)
}

class CodeCompletionSettingsState : BaseState() {
    var treeSitterProcessingEnabled by property(true)
    var gitDiffEnabled by property(true)
    var collectDependencyStructure by property(true)
    var contextAwareEnabled by property(false)
    var psiStructureAnalyzeDepth by property(2)
    var myAwesomeFeatureEnabled by property(true)
}
