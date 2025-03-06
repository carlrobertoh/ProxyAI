package ee.carlrobert.codegpt.settings.chat

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import ee.carlrobert.codegpt.CodeGPTBundle
import javax.swing.JComponent

class ChatConfigurationConfigurable : Configurable {

    private val editorContextTagCheckBox = JBCheckBox(
        CodeGPTBundle.get("chatConfigurationConfigurable.editorContextTag.title"),
        service<ChatSettings>().state.editorContextTagEnabled
    )

    private val psiStructureCheckBox = JBCheckBox(
        CodeGPTBundle.get("chatConfigurationConfigurable.psiStructure.title"),
        service<ChatSettings>().state.psiStructureEnabled
    )

    fun createPanel(): DialogPanel {
        return panel {
            row {
                cell(editorContextTagCheckBox)
                    .comment(CodeGPTBundle.get("chatConfigurationConfigurable.editorContextTag.description"))
            }
            row {
                cell(psiStructureCheckBox)
                    .comment(CodeGPTBundle.get("chatConfigurationConfigurable.psiStructure.description"))
            }
        }
    }

    fun resetForm(prevState: ChatSettingsState) {
        editorContextTagCheckBox.isSelected = prevState.editorContextTagEnabled
        psiStructureCheckBox.isSelected = prevState.psiStructureEnabled
    }

    override fun createComponent(): JComponent = createPanel()

    override fun isModified(): Boolean {
        return ChatSettingsState().apply {
            editorContextTagEnabled = editorContextTagCheckBox.isSelected
            psiStructureEnabled = psiStructureCheckBox.isSelected
        } != service<ChatSettings>().state
    }

    override fun apply() {
        service<ChatSettings>().loadState(
            ChatSettingsState().apply {
                editorContextTagEnabled = editorContextTagCheckBox.isSelected
                psiStructureEnabled = psiStructureCheckBox.isSelected
            }
        )
    }

    override fun getDisplayName(): String =
        CodeGPTBundle.get("chatConfigurationConfigurable.displayName")

}