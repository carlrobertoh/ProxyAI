package ee.carlrobert.codegpt.settings.service.inception

import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class InceptionSettingsForm {
    private val apiKeyField = JBPasswordField().apply { columns = 30 }

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(CodeGPTBundle.get("settingsConfigurable.shared.apiKey.label"), apiKeyField)
        .addComponentToRightColumn(
            UIUtil.createComment("settingsConfigurable.service.inception.apiKey.comment") as JLabel
        )
        .addComponentFillVertically(JPanel(), 0)
        .panel

    fun getForm(): JComponent = panel

    fun getApiKey(): String? = String(apiKeyField.password).ifEmpty { null }

    fun setApiKey(value: String?) {
        apiKeyField.text = value ?: ""
    }
}
