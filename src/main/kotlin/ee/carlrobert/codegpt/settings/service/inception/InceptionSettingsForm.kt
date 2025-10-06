package ee.carlrobert.codegpt.settings.service.inception

import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class InceptionSettingsForm {
    private val apiKeyField = JBPasswordField()
    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("API Key:", apiKeyField, 1, false)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    fun getForm(): JComponent = panel

    fun getApiKey(): String? = String(apiKeyField.password)

    fun setApiKey(value: String?) {
        apiKeyField.text = value ?: ""
    }
}
