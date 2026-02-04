package ee.carlrobert.codegpt.settings.skills

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.settings.skills.form.SkillsForm
import javax.swing.JComponent

class SkillsConfigurable(private val project: Project) : Configurable {
    private lateinit var form: SkillsForm

    override fun getDisplayName(): String = "ProxyAI: Skills"

    override fun createComponent(): JComponent {
        form = SkillsForm(project)
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
