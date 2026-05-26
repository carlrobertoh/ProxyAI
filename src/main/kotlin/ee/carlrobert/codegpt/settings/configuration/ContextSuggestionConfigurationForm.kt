package ee.carlrobert.codegpt.settings.configuration

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.components.fields.IntegerField
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

class ContextSuggestionConfigurationForm {

    private val maxFileSuggestionsField = IntegerField(
        "max_file_suggestions",
        1,
        ContextSuggestionSettings.MAX_FILE_SUGGESTIONS
    ).apply {
        columns = 12
        value = service<ConfigurationSettings>().state.contextSuggestionSettings.maxFileSuggestions
    }

    private val maxDirectorySuggestionsField = IntegerField(
        "max_directory_suggestions",
        1,
        ContextSuggestionSettings.MAX_DIRECTORY_SUGGESTIONS
    ).apply {
        columns = 12
        value = service<ConfigurationSettings>().state.contextSuggestionSettings.maxDirectorySuggestions
    }
    private val blankFileSuggestionModeComboBox =
        ComboBox(EnumComboBoxModel(ContextSuggestionBlankFileSuggestionMode::class.java)).apply {
            selectedItem = service<ConfigurationSettings>().state.contextSuggestionSettings.blankFileSuggestionMode
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val displayValue =
                        (value as? ContextSuggestionBlankFileSuggestionMode)?.displayName() ?: value
                    return super.getListCellRendererComponent(
                        list,
                        displayValue,
                        index,
                        isSelected,
                        cellHasFocus
                    )
                }
            }
        }
    private val fileSortModeComboBox =
        ComboBox(EnumComboBoxModel(ContextSuggestionFileSortMode::class.java)).apply {
            selectedItem = service<ConfigurationSettings>().state.contextSuggestionSettings.fileSortMode
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val displayValue = (value as? ContextSuggestionFileSortMode)?.displayName() ?: value
                    return super.getListCellRendererComponent(
                        list,
                        displayValue,
                        index,
                        isSelected,
                        cellHasFocus
                    )
                }
            }
        }
    private val pathDetailsModeComboBox =
        ComboBox(EnumComboBoxModel(ContextSuggestionPathDetailsMode::class.java)).apply {
            selectedItem = service<ConfigurationSettings>().state.contextSuggestionSettings.pathDetailsMode
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val displayValue = (value as? ContextSuggestionPathDetailsMode)?.displayName() ?: value
                    return super.getListCellRendererComponent(
                        list,
                        displayValue,
                        index,
                        isSelected,
                        cellHasFocus
                    )
                }
            }
        }

    fun createPanel(): DialogPanel {
        return panel {
            row {
                label(
                    CodeGPTBundle.get(
                        "configurationConfigurable.section.contextSuggestions.maxFileSuggestions.title"
                    )
                )
                cell(maxFileSuggestionsField)
                    .comment(
                        CodeGPTBundle.get(
                            "configurationConfigurable.section.contextSuggestions.maxFileSuggestions.description"
                        )
                    )
            }
            row {
                label(
                    CodeGPTBundle.get(
                        "configurationConfigurable.section.contextSuggestions.maxDirectorySuggestions.title"
                    )
                )
                cell(maxDirectorySuggestionsField)
                    .comment(
                        CodeGPTBundle.get(
                            "configurationConfigurable.section.contextSuggestions.maxDirectorySuggestions.description"
                        )
                    )
            }
            row {
                label(
                    CodeGPTBundle.get(
                        "configurationConfigurable.section.contextSuggestions.blankFileSuggestionMode.title"
                    )
                )
                cell(blankFileSuggestionModeComboBox)
                    .comment(
                        CodeGPTBundle.get(
                            "configurationConfigurable.section.contextSuggestions.blankFileSuggestionMode.description"
                        )
                    )
            }
            row {
                label(
                    CodeGPTBundle.get(
                        "configurationConfigurable.section.contextSuggestions.fileSortMode.title"
                    )
                )
                cell(fileSortModeComboBox)
                    .comment(
                        CodeGPTBundle.get(
                            "configurationConfigurable.section.contextSuggestions.fileSortMode.description"
                        )
                    )
            }
            row {
                label(
                    CodeGPTBundle.get(
                        "configurationConfigurable.section.contextSuggestions.pathDetailsMode.title"
                    )
                )
                cell(pathDetailsModeComboBox)
                    .comment(
                        CodeGPTBundle.get(
                            "configurationConfigurable.section.contextSuggestions.pathDetailsMode.description"
                        )
                    )
            }
        }.withBorder(JBUI.Borders.emptyLeft(16))
    }

    fun resetForm(prevState: ContextSuggestionSettingsState) {
        maxFileSuggestionsField.value = prevState.maxFileSuggestions
        maxDirectorySuggestionsField.value = prevState.maxDirectorySuggestions
        blankFileSuggestionModeComboBox.selectedItem = prevState.blankFileSuggestionMode
        fileSortModeComboBox.selectedItem = prevState.fileSortMode
        pathDetailsModeComboBox.selectedItem = prevState.pathDetailsMode
    }

    fun getFormState(): ContextSuggestionSettingsState {
        return ContextSuggestionSettingsState().apply {
            maxFileSuggestions = maxFileSuggestionsField.value
            maxDirectorySuggestions = maxDirectorySuggestionsField.value
            blankFileSuggestionMode =
                blankFileSuggestionModeComboBox.selectedItem as? ContextSuggestionBlankFileSuggestionMode
                    ?: ContextSuggestionBlankFileSuggestionMode.OPEN_AND_RECENT
            fileSortMode = fileSortModeComboBox.selectedItem as? ContextSuggestionFileSortMode
                ?: ContextSuggestionFileSortMode.PRESERVE_CURRENT_ORDER
            pathDetailsMode = pathDetailsModeComboBox.selectedItem as? ContextSuggestionPathDetailsMode
                ?: ContextSuggestionPathDetailsMode.FULL_PATH
        }
    }
}
