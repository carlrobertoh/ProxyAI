package ee.carlrobert.codegpt.settings.configuration

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.PortField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle

class ChatCompletionConfigurationForm {

    private val editorContextTagCheckBox = JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.section.chatCompletion.editorContextTag.title"),
        service<ConfigurationSettings>().state.chatCompletionSettings.editorContextTagEnabled
    )

    private val psiStructureCheckBox = JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.section.chatCompletion.psiStructure.title"),
        service<ConfigurationSettings>().state.chatCompletionSettings.psiStructureEnabled
    )

    private val psiStructureAnalyzeDepthField = PortField().apply {
        number = service<ConfigurationSettings>().state.chatCompletionSettings.psiStructureAnalyzeDepth
    }

    private val clickableLinksCheckBox = JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.section.chatCompletion.clickableLinks.title"),
        service<ConfigurationSettings>().state.chatCompletionSettings.clickableLinksEnabled
    )

    fun createPanel(): DialogPanel {
        return panel {
            row {
                cell(clickableLinksCheckBox)
                    .comment(CodeGPTBundle.get("configurationConfigurable.section.chatCompletion.clickableLinks.description"))
            }
            row {
                cell(editorContextTagCheckBox)
                    .comment(CodeGPTBundle.get("configurationConfigurable.section.chatCompletion.editorContextTag.description"))
            }
            row {
                cell(psiStructureCheckBox)
                    .comment(CodeGPTBundle.get("configurationConfigurable.section.chatCompletion.psiStructure.description"))
            }
            row {
                label(
                    CodeGPTBundle.get("configurationConfigurable.section.chatCompletion.psiStructure.analyzeDepth.title"),
                )
                cell(psiStructureAnalyzeDepthField)
                    .comment(CodeGPTBundle.get("configurationConfigurable.section.chatCompletion.psiStructure.analyzeDepth.comment"))
            }
        }.withBorder(JBUI.Borders.emptyLeft(16))
    }

    fun resetForm(prevState: ChatCompletionSettingsState) {
        editorContextTagCheckBox.isSelected = prevState.editorContextTagEnabled
        psiStructureCheckBox.isSelected = prevState.psiStructureEnabled
        psiStructureAnalyzeDepthField.number = prevState.psiStructureAnalyzeDepth
        clickableLinksCheckBox.isSelected = prevState.clickableLinksEnabled
    }

    fun getFormState(): ChatCompletionSettingsState {
        return ChatCompletionSettingsState().apply {
            this.editorContextTagEnabled = editorContextTagCheckBox.isSelected
            this.psiStructureEnabled = psiStructureCheckBox.isSelected
            this.psiStructureAnalyzeDepth = psiStructureAnalyzeDepthField.number
            this.clickableLinksEnabled = clickableLinksCheckBox.isSelected
        }
    }
}
