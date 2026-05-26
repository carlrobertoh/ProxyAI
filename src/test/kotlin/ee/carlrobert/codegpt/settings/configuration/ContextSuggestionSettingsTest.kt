package ee.carlrobert.codegpt.settings.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ContextSuggestionSettingsTest {

    @Test
    fun `defaults use the new production limits`() {
        val state = ContextSuggestionSettingsState()

        assertThat(state.maxFileSuggestions)
            .isEqualTo(ContextSuggestionSettings.DEFAULT_MAX_FILE_SUGGESTIONS)
        assertThat(state.maxDirectorySuggestions)
            .isEqualTo(ContextSuggestionSettings.DEFAULT_MAX_DIRECTORY_SUGGESTIONS)
        assertThat(state.blankFileSuggestionMode)
            .isEqualTo(ContextSuggestionBlankFileSuggestionMode.OPEN_AND_RECENT)
        assertThat(state.fileSortMode)
            .isEqualTo(ContextSuggestionFileSortMode.PRESERVE_CURRENT_ORDER)
        assertThat(state.pathDetailsMode)
            .isEqualTo(ContextSuggestionPathDetailsMode.FULL_PATH)
    }

    @Test
    fun `normalization clamps values to configured bounds`() {
        assertThat(ContextSuggestionSettings.normalizeMaxFileSuggestions(999))
            .isEqualTo(ContextSuggestionSettings.MAX_FILE_SUGGESTIONS)
        assertThat(ContextSuggestionSettings.normalizeMaxDirectorySuggestions(999))
            .isEqualTo(ContextSuggestionSettings.MAX_DIRECTORY_SUGGESTIONS)

        assertThat(ContextSuggestionSettings.normalizeMaxFileSuggestions(-5)).isEqualTo(1)
        assertThat(ContextSuggestionSettings.normalizeMaxDirectorySuggestions(0)).isEqualTo(1)
    }

    @Test
    fun `lookup result cap leaves headroom above configured file and directory limits`() {
        val state = ConfigurationSettingsState().apply {
            contextSuggestionSettings = ContextSuggestionSettingsState().apply {
                maxFileSuggestions = ContextSuggestionSettings.MAX_FILE_SUGGESTIONS
                maxDirectorySuggestions = ContextSuggestionSettings.MAX_DIRECTORY_SUGGESTIONS
            }
        }

        assertThat(ContextSuggestionSettings.maxLookupResults(state)).isEqualTo(602)
    }
}
