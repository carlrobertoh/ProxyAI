package ee.carlrobert.codegpt.settings.agents

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class AgentConfigurable : Configurable {

    override fun getDisplayName(): String {
        return "Agent"
    }

    override fun createComponent(): JComponent? {
        return null
    }

    override fun isModified(): Boolean {
        return false
    }

    override fun apply() {
    }

    override fun reset() {
    }
}