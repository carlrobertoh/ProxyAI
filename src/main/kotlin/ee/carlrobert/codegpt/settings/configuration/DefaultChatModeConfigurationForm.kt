package ee.carlrobert.codegpt.settings.configuration

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle

class DefaultChatModeConfigurationForm {

    private val editModeByDefaultCheckBox = JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.section.defaultChatMode.editModeByDefault.title"),
        service<ConfigurationSettings>().state.chatEditModeByDefault
    )

    fun createPanel(): DialogPanel {
        return panel {
            row {
                cell(editModeByDefaultCheckBox)
            }
        }.withBorder(JBUI.Borders.emptyLeft(16))
    }

    fun resetForm(prevState: ConfigurationSettingsState) {
        editModeByDefaultCheckBox.isSelected = prevState.chatEditModeByDefault
    }

    fun isEditModeByDefaultEnabled(): Boolean {
        return editModeByDefaultCheckBox.isSelected
    }
}
