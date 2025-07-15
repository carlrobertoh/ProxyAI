package ee.carlrobert.codegpt.settings.service.google;

import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.GoogleApiKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.settings.service.ModelReplacementDialog
import ee.carlrobert.codegpt.settings.service.ServiceType
import javax.swing.JComponent

class GoogleSettingsConfigurable : Configurable {

    private lateinit var component: GoogleSettingsForm

    override fun getDisplayName(): String {
        return "ProxyAI: Google Service"
    }

    override fun createComponent(): JComponent {
        component = GoogleSettingsForm()
        return component.getForm()
    }

    override fun isModified(): Boolean {
        return component.isModified() || component.getApiKey() != getCredential(GoogleApiKey)
    }

    override fun apply() {
        setCredential(GoogleApiKey, component.getApiKey())

        ModelReplacementDialog.showDialogIfNeeded(ServiceType.GOOGLE)
    }

    override fun reset() {
        component.resetForm()
    }
}