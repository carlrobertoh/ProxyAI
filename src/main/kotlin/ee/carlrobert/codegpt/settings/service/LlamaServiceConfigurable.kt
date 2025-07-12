package ee.carlrobert.codegpt.settings.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.completions.llama.LlamaServerAgent
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.llama.form.LlamaSettingsForm
import javax.swing.JComponent

class LlamaServiceConfigurable : Configurable, Disposable {

    private var form: LlamaSettingsForm? = null

    override fun getDisplayName(): String {
        return "CodeGPT: Llama"
    }

    override fun createComponent(): JComponent? {
        if (form == null) {
            form = LlamaSettingsForm(LlamaSettings.getCurrentState())

            ApplicationManager.getApplication().getService(LlamaServerAgent::class.java)
                .setSettingsForm(form)
        }
        return form
    }

    override fun isModified(): Boolean {
        val currentForm = form ?: return false
        return LlamaSettings.getInstance().isModified(currentForm)
    }

    override fun apply() {
        val currentForm = form ?: return
        LlamaSettings.getInstance().loadState(currentForm.currentState)
    }

    override fun reset() {
        form?.resetForm(LlamaSettings.getCurrentState())
    }

    override fun dispose() {
        form = null
    }
}