package ee.carlrobert.codegpt.settings.service

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class ServiceConfigurable : Configurable {

    private lateinit var component: ServiceConfigurableComponent

    override fun getDisplayName(): String {
        return "ProxyAI: Services"
    }

    override fun createComponent(): JComponent {
        component = ServiceConfigurableComponent()
        return component.getPanel()
    }

    override fun isModified(): Boolean {
        return false
    }

    override fun apply() {
        // No-op: service selection is now handled per feature in ModelSettings
    }

    override fun reset() {
        // No-op: service selection is now handled per feature in ModelSettings
    }
}