package ee.carlrobert.codegpt.settings.models

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.Disposer
import ee.carlrobert.codegpt.CodeGPTBundle
import javax.swing.JComponent

class ModelSettingsConfigurable : Configurable {
    private var form: ModelSettingsForm? = null
    
    override fun getDisplayName(): String {
        return CodeGPTBundle.get("settings.models.displayName")
    }
    
    override fun createComponent(): JComponent {
        form = ModelSettingsForm()
        return form!!.createPanel()
    }
    
    override fun isModified(): Boolean {
        return form?.isModified() == true
    }
    
    override fun apply() {
        form?.applyChanges()
    }
    
    override fun reset() {
        form?.resetForm()
    }
    
    override fun disposeUIResources() {
        form?.let { Disposer.dispose(it) }
        form = null
    }
}