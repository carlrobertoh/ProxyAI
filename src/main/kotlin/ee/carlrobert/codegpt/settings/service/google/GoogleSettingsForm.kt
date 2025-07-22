package ee.carlrobert.codegpt.settings.service.google

import com.intellij.openapi.components.service
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.GoogleApiKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.swing.JPanel

class GoogleSettingsForm {

    private val apiKeyField = JBPasswordField()

    init {
        apiKeyField.columns = 30
        apiKeyField.text = runBlocking(Dispatchers.IO) {
            getCredential(GoogleApiKey)
        }
    }

    fun getForm(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.shared.apiKey.label"),
            apiKeyField
        )
        .addComponentToRightColumn(
            UIUtil.createComment("settingsConfigurable.service.google.apiKey.comment")
        )
        .addComponentFillVertically(JPanel(), 0)
        .panel

    fun getApiKey(): String? = String(apiKeyField.password).ifEmpty { null }

    fun resetForm() {
        apiKeyField.text = getCredential(GoogleApiKey)
    }

    fun isModified(): Boolean = service<GoogleSettings>().state.run {
        getApiKey() != getCredential(GoogleApiKey)
    }
}
