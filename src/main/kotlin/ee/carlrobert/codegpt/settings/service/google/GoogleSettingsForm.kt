package ee.carlrobert.codegpt.settings.service.google

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.GoogleApiKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.ui.UIUtil
import ee.carlrobert.llm.client.google.models.GoogleModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel

class GoogleSettingsForm {

    private val apiKeyField = JBPasswordField()
    private val completionModelComboBox: ComboBox<GoogleModel>

    init {
        val state = service<GoogleSettings>().state
        apiKeyField.columns = 30
        apiKeyField.text = runBlocking(Dispatchers.IO) {
            getCredential(GoogleApiKey)
        }
        completionModelComboBox = ComboBox(
            DefaultComboBoxModel(
                arrayOf(
                    GoogleModel.GEMINI_2_0_PRO_EXP,
                    GoogleModel.GEMINI_2_0_FLASH_THINKING_EXP,
                    GoogleModel.GEMINI_2_0_FLASH,
                    GoogleModel.GEMINI_1_5_PRO
                )
            )
        )
        completionModelComboBox.selectedItem = GoogleModel.findByCode(state.model)
    }

    fun getForm(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.shared.apiKey.label"),
            apiKeyField
        )
        .addComponentToRightColumn(
            UIUtil.createComment("settingsConfigurable.service.google.apiKey.comment")
        )
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.shared.model.label"),
            completionModelComboBox
        )
        .addComponentToRightColumn(
            UIUtil.createComment("settingsConfigurable.service.google.model.comment")
        )
        .addComponentFillVertically(JPanel(), 0)
        .panel

    fun getApiKey(): String? = String(apiKeyField.password).ifEmpty { null }

    fun getModel(): String = (completionModelComboBox.model
        .selectedItem as GoogleModel)
        .code

    fun getCurrentState() = GoogleSettingsState().apply { model = getModel() }

    fun resetForm() {
        val state = service<GoogleSettings>().state
        apiKeyField.text = getCredential(GoogleApiKey)
        completionModelComboBox.selectedItem = GoogleModel.findByCode(state.model)
    }

    fun isModified(): Boolean = service<GoogleSettings>().state.run {
        model != getModel() || getApiKey() != getCredential(GoogleApiKey)
    }

    fun applyChanges() {
        service<GoogleSettings>().state.run {
            model = getModel()
        }
    }
}
