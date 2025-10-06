package ee.carlrobert.codegpt.settings.service

import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.credentials.CredentialsStore
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.InceptionApiKey
import ee.carlrobert.codegpt.settings.service.inception.InceptionSettingsForm
import javax.swing.JComponent

class InceptionServiceConfigurable : Configurable {

    private lateinit var component: InceptionSettingsForm

    override fun getDisplayName(): String {
        return "ProxyAI: Inception Service"
    }

    override fun createComponent(): JComponent {
        component = InceptionSettingsForm()
        component.setApiKey(CredentialsStore.getCredential(InceptionApiKey))
        return component.getForm()
    }

    override fun isModified(): Boolean {
        return component.getApiKey() != CredentialsStore.getCredential(InceptionApiKey)
    }

    override fun apply() {
        CredentialsStore.setCredential(InceptionApiKey, component.getApiKey())
        ModelReplacementDialog.showDialogIfNeeded(ServiceType.INCEPTION)
    }

    override fun reset() {
        component.setApiKey(CredentialsStore.getCredential(InceptionApiKey))
    }
}
