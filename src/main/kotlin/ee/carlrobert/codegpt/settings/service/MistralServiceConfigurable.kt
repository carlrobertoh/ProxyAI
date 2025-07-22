package ee.carlrobert.codegpt.settings.service

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.MistralApiKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.settings.service.mistral.MistralSettings
import ee.carlrobert.codegpt.settings.service.mistral.MistralSettingsForm
import javax.swing.JComponent

class MistralServiceConfigurable : Configurable {

    private lateinit var component: MistralSettingsForm

    override fun getDisplayName(): String {
        return "ProxyAI: Mistral Service"
    }

    override fun createComponent(): JComponent {
        component = MistralSettingsForm(service<MistralSettings>().state)
        return component.form
    }

    override fun isModified(): Boolean {
        return component.getCurrentState() != service<MistralSettings>().state
                || component.getApiKey() != getCredential(MistralApiKey)
    }

    override fun apply() {
        setCredential(MistralApiKey, component.getApiKey())
        service<MistralSettings>().loadState(component.getCurrentState())

        ModelReplacementDialog.showDialogIfNeeded(ServiceType.MISTRAL)
    }

    override fun reset() {
        component.resetForm()
    }
}