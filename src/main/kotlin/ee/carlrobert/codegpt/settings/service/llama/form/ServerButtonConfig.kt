package ee.carlrobert.codegpt.settings.service.llama.form

import com.intellij.ui.PortField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.IntegerField

data class ServerButtonConfig(
    val portField: PortField,
    val contextSizeField: IntegerField,
    val threadsField: IntegerField,
    val additionalParametersField: JBTextField,
    val additionalBuildParametersField: JBTextField,
    val additionalEnvironmentVariablesField: JBTextField
)