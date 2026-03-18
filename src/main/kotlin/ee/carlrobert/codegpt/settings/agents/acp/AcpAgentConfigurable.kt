package ee.carlrobert.codegpt.settings.agents.acp

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.JComponent

class AcpAgentConfigurable(private val project: Project) : Configurable {

    private lateinit var form: AcpAgentSettingsForm

    override fun getDisplayName(): String = "ProxyAI: ACP"

    override fun createComponent(): JComponent {
        form = AcpAgentSettingsForm(project)
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
