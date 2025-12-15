package ee.carlrobert.codegpt.settings.agents

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.settings.agents.form.SubagentsForm
import javax.swing.JComponent

class SubagentsConfigurable(private val project: Project) : Configurable {
    private lateinit var form: SubagentsForm

    override fun getDisplayName(): String = "ProxyAI: Subagents"

    override fun createComponent(): JComponent {
        form = SubagentsForm(project)
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
