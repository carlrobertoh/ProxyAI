package ee.carlrobert.codegpt.settings.service

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.AzureActiveDirectoryToken
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.AzureOpenaiApiKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.azure.AzureSettings
import ee.carlrobert.codegpt.settings.service.azure.AzureSettingsForm
import javax.swing.JComponent

class AzureServiceConfigurable : Configurable {

    private lateinit var component: AzureSettingsForm

    override fun getDisplayName(): String {
        return "ProxyAI: Azure Service"
    }

    override fun createComponent(): JComponent {
        component = AzureSettingsForm(service<AzureSettings>().state)
        return component.getForm()
    }

    override fun isModified(): Boolean {
        return component.getCurrentState() != service<AzureSettings>().state
                || component.getActiveDirectoryToken() != getCredential(AzureActiveDirectoryToken)
                || component.getApiKey() != getCredential(AzureOpenaiApiKey)
    }

    override fun apply() {
        service<GeneralSettings>().state.selectedService = ServiceType.AZURE
        service<AzureSettings>().loadState(component.currentState)
        setCredential(AzureOpenaiApiKey, component.getApiKey())
        setCredential(AzureActiveDirectoryToken, component.getActiveDirectoryToken())
    }

    override fun reset() {
        component.resetForm()
    }
}