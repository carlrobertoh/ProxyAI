package ee.carlrobert.codegpt.settings.prompts

import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.actions.editor.EditorActionsUtil
import ee.carlrobert.codegpt.settings.prompts.form.PromptsForm
import javax.swing.JComponent

class PromptsConfigurable : Configurable {

    private lateinit var component: PromptsForm

    override fun getDisplayName(): String {
        return "ProxyAI: Prompts"
    }

    override fun createComponent(): JComponent {
        component = PromptsForm()
        return component.createPanel()
    }

    override fun isModified(): Boolean = component.isModified()

    override fun apply() {
        component.applyChanges()
        EditorActionsUtil.refreshActions()
    }

    override fun reset() {
        component.resetChanges()
    }
}