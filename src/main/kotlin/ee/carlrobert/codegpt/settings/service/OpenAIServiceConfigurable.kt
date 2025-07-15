package ee.carlrobert.codegpt.settings.service

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.OpenaiApiKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettingsForm
import javax.swing.JComponent

class OpenAIServiceConfigurable : Configurable {

    private lateinit var component: OpenAISettingsForm

    override fun getDisplayName(): String {
        return "ProxyAI: OpenAI Service"
    }

    override fun createComponent(): JComponent {
        component = OpenAISettingsForm(service<OpenAISettings>().state)
        return component.form
    }

    override fun isModified(): Boolean {
        return component.getCurrentState() != service<OpenAISettings>().state
                || component.getApiKey() != getCredential(OpenaiApiKey)
    }

    override fun apply() {
        setCredential(OpenaiApiKey, component.getApiKey())
        service<OpenAISettings>().loadState(component.getCurrentState())

        ModelReplacementDialog.showDialogIfNeeded(ServiceType.OPENAI)
    }

    override fun reset() {
        component.resetForm()
    }
}