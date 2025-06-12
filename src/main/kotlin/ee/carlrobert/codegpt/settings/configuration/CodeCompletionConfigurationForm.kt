package ee.carlrobert.codegpt.settings.configuration

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.PortField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.fields.IntegerField
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle

class CodeCompletionConfigurationForm {

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

    private val psiStructureAnalyzeDepthField = PortField().apply {
        number = service<ConfigurationSettings>().state.codeCompletionSettings.psiStructureAnalyzeDepth
    }

    fun createPanel(): DialogPanel {
        return panel {
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
            row {
                label(
                    CodeGPTBundle.get("configurationConfigurable.section.codeCompletion.analyzeDepth.title"),
                )
                cell(psiStructureAnalyzeDepthField)
                    .comment(CodeGPTBundle.get("configurationConfigurable.section.codeCompletion.analyzeDepth.comment"))
            }
        }.withBorder(JBUI.Borders.emptyLeft(16))
    }

    fun resetForm(prevState: CodeCompletionSettingsState) {
        treeSitterProcessingCheckBox.isSelected = prevState.treeSitterProcessingEnabled
        gitDiffCheckBox.isSelected = prevState.gitDiffEnabled
        psiStructureAnalyzeDepthField.number = prevState.psiStructureAnalyzeDepth
    }

    fun getFormState(): CodeCompletionSettingsState {
        return CodeCompletionSettingsState().apply {
            this.treeSitterProcessingEnabled = treeSitterProcessingCheckBox.isSelected
            this.gitDiffEnabled = gitDiffCheckBox.isSelected
            this.collectDependencyStructure = collectDependencyStructureBox.isSelected
            this.psiStructureAnalyzeDepth = psiStructureAnalyzeDepthField.number
        }
    }
}