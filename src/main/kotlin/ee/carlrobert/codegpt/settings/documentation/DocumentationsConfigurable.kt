package ee.carlrobert.codegpt.settings.documentation

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class DocumentationsConfigurable : Configurable {

    private lateinit var component: DocumentationsSettingsForm

    override fun getDisplayName(): String {
        return "ProxyAI: Documentations"
    }

    override fun createComponent(): JComponent {
        component = DocumentationsSettingsForm()
        return component.createPanel()
    }

    override fun isModified(): Boolean = component.isModified()

    override fun apply() {
        component.applyChanges()
    }

    override fun reset() {
        component.resetChanges()
    }
}