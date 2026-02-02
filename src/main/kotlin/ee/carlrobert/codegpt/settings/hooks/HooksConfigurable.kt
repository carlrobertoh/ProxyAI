package ee.carlrobert.codegpt.settings.hooks

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.settings.hooks.form.HooksForm
import javax.swing.JComponent

class HooksConfigurable(private val project: Project) : Configurable {
    private lateinit var form: HooksForm

    override fun getDisplayName(): String = "ProxyAI: Hooks"

    override fun createComponent(): JComponent {
        form = HooksForm(project)
        return form.createPanel()
    }

    override fun isModified(): Boolean = form.isModified()

    override fun apply() {
        form.applyChanges()
    }

    override fun reset() {
        form.resetChanges()
    }
}
