package ee.carlrobert.codegpt.settings.configuration

import com.intellij.openapi.components.*
import ee.carlrobert.codegpt.CodeGPTBundle
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
    var checkForNewScreenshots by property(false)
    var screenshotWatchPaths by list<String>()
    var ignoreGitCommitTokenLimit by property(false)
    var methodNameGenerationEnabled by property(true)
    var captureCompileErrors by property(true)
    var autoFormattingEnabled by property(true)
    var tableData by map<String, String>()
    var chatCompletionSettings by property(ChatCompletionSettingsState())
    var codeCompletionSettings by property(CodeCompletionSettingsState())
    var contextSuggestionSettings by property(ContextSuggestionSettingsState())

    init {
        tableData.putAll(EditorActionsUtil.DEFAULT_ACTIONS)

        if (screenshotWatchPaths.isEmpty()) {
            screenshotWatchPaths.addAll(ScreenshotPathDetector.getDefaultPaths())
        }
    }
}

class ChatCompletionSettingsState : BaseState() {
    var chatEditModeByDefault by property(false)
    var editorContextTagEnabled by property(true)
    var psiStructureEnabled by property(true)
    var psiStructureAnalyzeDepth by property(3)
    var clickableLinksEnabled by property(true)
    var sendWithAltEnter by property(false)
    var sendWithCtrlEnter by property(false)
    var sendWithShiftEnter by property(false)
}

object ContextSuggestionSettings {
    const val DEFAULT_MAX_FILE_SUGGESTIONS = 50
    const val DEFAULT_MAX_DIRECTORY_SUGGESTIONS = 25
    const val MAX_FILE_SUGGESTIONS = 500
    const val MAX_DIRECTORY_SUGGESTIONS = 70

    private const val MIN_SUGGESTIONS = 1
    private const val LOOKUP_RESULT_HEADROOM = 32

    fun normalizeMaxFileSuggestions(value: Int): Int =
        value.coerceIn(MIN_SUGGESTIONS, MAX_FILE_SUGGESTIONS)

    fun normalizeMaxDirectorySuggestions(value: Int): Int =
        value.coerceIn(MIN_SUGGESTIONS, MAX_DIRECTORY_SUGGESTIONS)

    fun maxLookupResults(state: ConfigurationSettingsState = ConfigurationSettings.getState()): Int {
        val fileLimit = normalizeMaxFileSuggestions(state.contextSuggestionSettings.maxFileSuggestions)
        val directoryLimit =
            normalizeMaxDirectorySuggestions(state.contextSuggestionSettings.maxDirectorySuggestions)
        return fileLimit + directoryLimit + LOOKUP_RESULT_HEADROOM
    }
}

class ContextSuggestionSettingsState : BaseState() {
    var maxFileSuggestions by property(ContextSuggestionSettings.DEFAULT_MAX_FILE_SUGGESTIONS)
    var maxDirectorySuggestions by property(ContextSuggestionSettings.DEFAULT_MAX_DIRECTORY_SUGGESTIONS)
    var blankFileSuggestionMode by enum(ContextSuggestionBlankFileSuggestionMode.OPEN_AND_RECENT)
    var fileSortMode by enum(ContextSuggestionFileSortMode.PRESERVE_CURRENT_ORDER)
    var pathDetailsMode by enum(ContextSuggestionPathDetailsMode.FULL_PATH)
}

enum class ContextSuggestionBlankFileSuggestionMode(private val messageKey: String) {
    OPEN_AND_RECENT("configurationConfigurable.section.contextSuggestions.blankFileSuggestionMode.option.openAndRecent"),
    OPEN_RECENT_AND_PROJECT("configurationConfigurable.section.contextSuggestions.blankFileSuggestionMode.option.openRecentAndProject");

    fun displayName(): String = CodeGPTBundle.get(messageKey)
}

enum class ContextSuggestionFileSortMode(private val messageKey: String) {
    FILE_NAME_ASCENDING("configurationConfigurable.section.contextSuggestions.fileSortMode.option.fileNameAscending"),
    FOLDER_THEN_FILE_ASCENDING("configurationConfigurable.section.contextSuggestions.fileSortMode.option.folderThenFileAscending"),
    PRESERVE_CURRENT_ORDER("configurationConfigurable.section.contextSuggestions.fileSortMode.option.preserveCurrentOrder");

    fun displayName(): String = CodeGPTBundle.get(messageKey)
}

enum class ContextSuggestionPathDetailsMode(private val messageKey: String) {
    FULL_PATH("configurationConfigurable.section.contextSuggestions.pathDetailsMode.option.fullPath"),
    DIRECTORY_ONLY("configurationConfigurable.section.contextSuggestions.pathDetailsMode.option.directoryOnly");

    fun displayName(): String = CodeGPTBundle.get(messageKey)
}

class CodeCompletionSettingsState : BaseState() {
    var treeSitterProcessingEnabled by property(true)
    var gitDiffEnabled by property(true)
    var collectDependencyStructure by property(false)
    var contextAwareEnabled by property(false)
    var psiStructureAnalyzeDepth by property(2)
}
