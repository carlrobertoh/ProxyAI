package ee.carlrobert.codegpt.settings.service.codegpt

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CodeGptApiKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.util.ApplicationUtil
import javax.swing.JComponent

class CodeGPTServiceConfigurable : Configurable {

    private lateinit var component: CodeGPTServiceForm
    private var uiComponent: JComponent? = null

    override fun getDisplayName(): String {
        return "ProxyAI: ProxyAI Service"
    }

    override fun createComponent(): JComponent {
        component = CodeGPTServiceForm()
        uiComponent = component.getForm()
        return uiComponent as JComponent
    }

    override fun isModified(): Boolean {
        return component.isModified() || component.getApiKey() != getCredential(CodeGptApiKey)
    }

    override fun apply() {
        setCredential(CodeGptApiKey, component.getApiKey())
        component.applyChanges()

        val modality = ModalityState.stateForComponent(uiComponent ?: component.getForm())
        ApplicationUtil.findCurrentProject()
            ?.service<CodeGPTService>()
            ?.syncUserDetailsAsync(component.getApiKey(), true, modality)
    }

    override fun reset() {
        component.resetForm()
    }
}
