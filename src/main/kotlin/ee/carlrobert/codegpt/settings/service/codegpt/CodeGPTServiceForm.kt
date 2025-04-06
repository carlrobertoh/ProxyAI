package ee.carlrobert.codegpt.settings.service.codegpt

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CodeGptApiKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jdesktop.swingx.combobox.ListComboBoxModel
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JPanel

class CodeGPTServiceForm {

    private val apiKeyField = JBPasswordField().apply {
        columns = 30
    }

    private val chatCompletionModelComboBox =
        ComboBox(ListComboBoxModel(CodeGPTAvailableModels.ALL_CHAT_MODELS)).apply {
            val chatModel = service<CodeGPTServiceSettings>().state.chatCompletionSettings.model
            selectedItem = CodeGPTAvailableModels.findByCode(chatModel)
                ?: CodeGPTAvailableModels.DEFAULT_CHAT_MODEL
            renderer = CustomComboBoxRenderer()
        }

    private val enableNextEditsEnabledCheckBox = JBCheckBox(
        "Enable next edits",
        service<CodeGPTServiceSettings>().state.nextEditsEnabled
    )

    private val codeCompletionsEnabledCheckBox = JBCheckBox(
        CodeGPTBundle.get("codeCompletionsForm.enableFeatureText"),
        service<CodeGPTServiceSettings>().state.codeCompletionSettings.codeCompletionsEnabled
    )

    private val codeCompletionModelComboBox =
        ComboBox(ListComboBoxModel(CodeGPTAvailableModels.ALL_CODE_MODELS)).apply {
            val codeModel = service<CodeGPTServiceSettings>().state.codeCompletionSettings.model
            selectedItem = CodeGPTAvailableModels.findByCode(codeModel)
                ?: CodeGPTAvailableModels.DEFAULT_CODE_MODEL
            renderer = CustomComboBoxRenderer()
        }

    init {
        apiKeyField.text = runBlocking(Dispatchers.IO) {
            getCredential(CodeGptApiKey)
        }
    }

    fun getForm(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.shared.apiKey.label"),
            apiKeyField
        )
        .addComponentToRightColumn(
            UIUtil.createComment("settingsConfigurable.service.codegpt.apiKey.comment")
        )
        .addLabeledComponent("Chat model:", chatCompletionModelComboBox)
        .addComponentToRightColumn(
            UIUtil.createComment("settingsConfigurable.service.codegpt.chatCompletionModel.comment")
        )
        .addLabeledComponent("Code model:", codeCompletionModelComboBox)
        .addComponentToRightColumn(
            UIUtil.createComment("settingsConfigurable.service.codegpt.codeCompletionModel.comment")
        )
        .addVerticalGap(4)
        .addComponent(enableNextEditsEnabledCheckBox)
        .addComponent(
            UIUtil.createComment("settingsConfigurable.service.codegpt.enableNextEdits.comment", 90)
        )
        .addVerticalGap(4)
        .addComponent(codeCompletionsEnabledCheckBox)
        .addComponent(
            UIUtil.createComment("settingsConfigurable.service.codegpt.enableCodeCompletion.comment", 90)
        )
        .addComponentFillVertically(JPanel(), 0)
        .panel

    fun getApiKey() = String(apiKeyField.password).ifEmpty { null }

    fun isModified() = service<CodeGPTServiceSettings>().state.run {
        (chatCompletionModelComboBox.selectedItem as CodeGPTModel).code != chatCompletionSettings.model
                || (codeCompletionModelComboBox.selectedItem as CodeGPTModel).code != codeCompletionSettings.model
                || enableNextEditsEnabledCheckBox.isSelected != nextEditsEnabled
                || codeCompletionsEnabledCheckBox.isSelected != codeCompletionSettings.codeCompletionsEnabled
                || getApiKey() != getCredential(CodeGptApiKey)
    }

    fun applyChanges() {
        service<CodeGPTServiceSettings>().state.run {
            nextEditsEnabled = enableNextEditsEnabledCheckBox.isSelected
            chatCompletionSettings.model =
                (chatCompletionModelComboBox.selectedItem as CodeGPTModel).code
            codeCompletionSettings.codeCompletionsEnabled =
                codeCompletionsEnabledCheckBox.isSelected
            codeCompletionSettings.model =
                (codeCompletionModelComboBox.selectedItem as CodeGPTModel).code
        }
        setCredential(CodeGptApiKey, getApiKey())
    }

    fun resetForm() {
        service<CodeGPTServiceSettings>().state.run {
            enableNextEditsEnabledCheckBox.isSelected = nextEditsEnabled
            chatCompletionModelComboBox.selectedItem = chatCompletionSettings.model
            codeCompletionModelComboBox.selectedItem = codeCompletionSettings.model
            codeCompletionsEnabledCheckBox.isSelected =
                codeCompletionSettings.codeCompletionsEnabled
        }
        apiKeyField.text = getCredential(CodeGptApiKey)
    }

    private class CustomComboBoxRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component =
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is CodeGPTModel) {
                text = value.name
            }
            return component
        }
    }
}