package ee.carlrobert.codegpt.toolwindow.chat.editorsettings

import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class EditorTagSettingsViewModel(
    private val tagManager: TagManager,
) {

    private val _state: MutableStateFlow<EditorTagSettingsState> = MutableStateFlow(
        EditorTagSettingsState(editorTagsEnabled = true)
    )
    val state: StateFlow<EditorTagSettingsState> = _state.asStateFlow()

    fun disableEditorTags() {
        tagManager.disableEditorTags()
        _state.update { it.copy(editorTagsEnabled = false) }
    }

    fun enableEditorTags() {
        tagManager.enableEditorTags()
        _state.update { it.copy(editorTagsEnabled = true) }
    }
}
