package ee.carlrobert.codegpt.settings.service.codegpt

import com.intellij.openapi.components.service
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CodeGptApiKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.ui.UIUtil
import ee.carlrobert.codegpt.util.ApplicationUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JPanel

class CodeGPTServiceForm {

    private val apiKeyField = JBPasswordField().apply {
        columns = 30
    }

    private val enableNextEditsEnabledCheckBox = JBCheckBox(
        "Enable next edits",
        service<CodeGPTServiceSettings>().state.nextEditsEnabled
    )

    private val codeCompletionsEnabledCheckBox = JBCheckBox(
        CodeGPTBundle.get("codeCompletionsForm.enableFeatureText"),
        service<CodeGPTServiceSettings>().state.codeCompletionSettings.codeCompletionsEnabled
    )

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
        .addVerticalGap(4)
        .addComponent(codeCompletionsEnabledCheckBox)
        .addComponent(
            UIUtil.createComment(
                "settingsConfigurable.service.codegpt.enableCodeCompletion.comment",
                90
            )
        )
        .addVerticalGap(4)
        .addComponent(enableNextEditsEnabledCheckBox)
        .addComponent(
            UIUtil.createComment("settingsConfigurable.service.codegpt.enableNextEdits.comment", 90)
        )
        .addComponentFillVertically(JPanel(), 0)
        .panel

    fun getApiKey() = String(apiKeyField.password).ifEmpty { null }

    fun isModified() = service<CodeGPTServiceSettings>().state.run {
        enableNextEditsEnabledCheckBox.isSelected != nextEditsEnabled
                || codeCompletionsEnabledCheckBox.isSelected != codeCompletionSettings.codeCompletionsEnabled
                || getApiKey() != getCredential(CodeGptApiKey)
    }

    fun applyChanges() {
        service<CodeGPTServiceSettings>().state.run {
            nextEditsEnabled = enableNextEditsEnabledCheckBox.isSelected
            codeCompletionSettings.codeCompletionsEnabled =
                codeCompletionsEnabledCheckBox.isSelected
        }
        setCredential(CodeGptApiKey, getApiKey())

        ApplicationUtil.findCurrentProject()?.service<GrpcClientService>()?.refreshConnection()
    }

    fun resetForm() {
        service<CodeGPTServiceSettings>().state.run {
            enableNextEditsEnabledCheckBox.isSelected = nextEditsEnabled
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