package ee.carlrobert.codegpt.settings.configuration

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle

class CodeCompletionConfigurationForm {

    private val multiLineCompletionsCheckBox = JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.section.codeCompletion.multiLineCompletions.title"),
        service<ConfigurationSettings>().state.codeCompletionSettings.multiLineEnabled
    )
    private val treeSitterProcessingCheckBox = JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.section.codeCompletion.postProcess.title"),
        service<ConfigurationSettings>().state.codeCompletionSettings.treeSitterProcessingEnabled
    )
    private val gitDiffCheckBox = JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.section.codeCompletion.gitDiff.title"),
        service<ConfigurationSettings>().state.codeCompletionSettings.gitDiffEnabled
    )

    private val collectDependencyStructureBox = JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.section.codeCompletion.collectDependencyStructure.title"),
        service<ConfigurationSettings>().state.codeCompletionSettings.collectDependencyStructure
    )

    fun createPanel(): DialogPanel {
        return panel {
            row {
                cell(multiLineCompletionsCheckBox)
                    .comment(CodeGPTBundle.get("configurationConfigurable.section.codeCompletion.multiLineCompletions.description"))
            }
            row {
                cell(treeSitterProcessingCheckBox)
                    .comment(CodeGPTBundle.get("configurationConfigurable.section.codeCompletion.postProcess.description"))
            }
            row {
                cell(gitDiffCheckBox)
                    .comment(CodeGPTBundle.get("configurationConfigurable.section.codeCompletion.gitDiff.description"))
            }
            row {
                cell(collectDependencyStructureBox)
                    .comment(CodeGPTBundle.get("configurationConfigurable.section.codeCompletion.collectDependencyStructure.description"))
            }
        }.withBorder(JBUI.Borders.emptyLeft(16))
    }

    fun resetForm(prevState: CodeCompletionSettingsState) {
        multiLineCompletionsCheckBox.isSelected = prevState.multiLineEnabled
        treeSitterProcessingCheckBox.isSelected = prevState.treeSitterProcessingEnabled
        gitDiffCheckBox.isSelected = prevState.gitDiffEnabled
    }

    fun getFormState(): CodeCompletionSettingsState {
        return CodeCompletionSettingsState().apply {
            this.multiLineEnabled = multiLineCompletionsCheckBox.isSelected
            this.treeSitterProcessingEnabled = treeSitterProcessingCheckBox.isSelected
            this.gitDiffEnabled = gitDiffCheckBox.isSelected
            this.collectDependencyStructure = collectDependencyStructureBox.isSelected
        }
    }
}