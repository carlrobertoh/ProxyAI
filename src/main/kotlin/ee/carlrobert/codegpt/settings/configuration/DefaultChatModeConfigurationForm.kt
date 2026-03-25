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
    private val rememberAttachedFilesToChatCheckBox = JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.section.defaultChatMode.rememberAttachedFilesToChat.title"),
        service<ConfigurationSettings>().state.rememberAttachedFilesToChat
    )

    fun createPanel(): DialogPanel {
        return panel {
            row {
                cell(editModeByDefaultCheckBox)
            }
            row {
                cell(rememberAttachedFilesToChatCheckBox)
            }
        }.withBorder(JBUI.Borders.emptyLeft(16))
    }

    fun resetForm(prevState: ConfigurationSettingsState) {
        editModeByDefaultCheckBox.isSelected = prevState.chatEditModeByDefault
        rememberAttachedFilesToChatCheckBox.isSelected = prevState.rememberAttachedFilesToChat
    }

    fun isEditModeByDefaultEnabled(): Boolean {
        return editModeByDefaultCheckBox.isSelected
    }

    fun isRememberAttachedFilesToChatEnabled(): Boolean {
        return rememberAttachedFilesToChatCheckBox.isSelected
    }
}
