package ee.carlrobert.codegpt.settings.service.codegpt

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CodeGptApiKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.util.ApplicationUtil
import javax.swing.JComponent

class CodeGPTServiceConfigurable : Configurable {

    private lateinit var component: CodeGPTServiceForm

    override fun getDisplayName(): String {
        return "ProxyAI: ProxyAI Service"
    }

    override fun createComponent(): JComponent {
        component = CodeGPTServiceForm()
        return component.getForm()
    }

    override fun isModified(): Boolean {
        return component.isModified() || component.getApiKey() != getCredential(CodeGptApiKey)
    }

    override fun apply() {
        setCredential(CodeGptApiKey, component.getApiKey())
        component.applyChanges()

        ApplicationUtil.findCurrentProject()
            ?.service<CodeGPTService>()
            ?.syncUserDetailsAsync(component.getApiKey(), true)
    }

    override fun reset() {
        component.resetForm()
    }
}