package ee.carlrobert.codegpt.settings.service.llama.form

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.MessageType
import com.intellij.ui.PortField
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.IntegerField
import com.intellij.util.ui.FormBuilder
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.completions.llama.LlamaServerAgent
import ee.carlrobert.codegpt.completions.llama.LlamaServerStartupParams
import ee.carlrobert.codegpt.completions.llama.PromptTemplate
import ee.carlrobert.codegpt.completions.llama.logging.NoOpLoggingStrategy
import ee.carlrobert.codegpt.completions.llama.logging.SettingsFormLoggingStrategy
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettingsState
import ee.carlrobert.codegpt.settings.service.llama.form.LlamaSettingsForm
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.ui.UIUtil
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class LlamaServerPreferencesForm(settings: LlamaSettingsState, private val parentForm: LlamaSettingsForm? = null) {
    val llamaModelPreferencesForm = LlamaModelPreferencesForm()

    private val portField: PortField
    private val maxTokensField: IntegerField
    private val threadsField: IntegerField
    private val additionalParametersField: JBTextField
    private val additionalBuildParametersField: JBTextField
    private val additionalEnvironmentVariablesField: JBTextField

    init {
        val llamaServerAgent = ApplicationManager.getApplication().getService(LlamaServerAgent::class.java)
        val serverRunning = llamaServerAgent.isServerRunning

        portField = PortField(settings.serverPort).apply {
            isEnabled = !serverRunning
        }

        maxTokensField = IntegerField("max_tokens", 256, 4096).apply {
            columns = 12
            value = settings.contextSize
            isEnabled = !serverRunning
        }

        threadsField = IntegerField("threads", 1, 256).apply {
            columns = 12
            value = settings.threads
            isEnabled = !serverRunning
        }

        additionalParametersField = JBTextField(settings.additionalParameters, 30).apply {
            isEnabled = !serverRunning
        }

        additionalBuildParametersField = JBTextField(settings.additionalBuildParameters, 30).apply {
            isEnabled = !serverRunning
        }

        additionalEnvironmentVariablesField = JBTextField(settings.additionalEnvironmentVariables, 30).apply {
            isEnabled = !serverRunning
        }
    }

    fun getForm(): JPanel {
        val llamaServerAgent = ApplicationManager.getApplication().getService(LlamaServerAgent::class.java)
        return createRunLocalServerForm(llamaServerAgent)
    }

    fun resetForm(state: LlamaSettingsState) {
        llamaModelPreferencesForm.resetForm(state)

        portField.number = state.serverPort
        maxTokensField.value = state.contextSize
        threadsField.value = state.threads
        additionalParametersField.text = state.additionalParameters
        additionalBuildParametersField.text = state.additionalBuildParameters
        additionalEnvironmentVariablesField.text = state.additionalEnvironmentVariables
    }

    fun createRunLocalServerForm(llamaServerAgent: LlamaServerAgent): JPanel {
        return UIUtil.withEmptyLeftBorder(
            FormBuilder.createFormBuilder()
                .addComponent(
                    TitledSeparator(
                        CodeGPTBundle.get("settingsConfigurable.service.llama.modelPreferences.title")
                    )
                )
                .addComponent(UIUtil.withEmptyLeftBorder(llamaModelPreferencesForm.form))
                .addComponent(TitledSeparator(CodeGPTBundle.get("llama.ui.tab.serverConfiguration")))
                .addComponent(
                    UIUtil.withEmptyLeftBorder(
                        FormBuilder.createFormBuilder()
                            .addLabeledComponent(
                                CodeGPTBundle.get("shared.port"),
                                createPortAndButtonsPanel(llamaServerAgent)
                            )
                            .addVerticalGap(4)
                            .addLabeledComponent(
                                CodeGPTBundle.get("settingsConfigurable.service.llama.contextSize.label"),
                                maxTokensField
                            )
                            .addComponentToRightColumn(
                                UIUtil.createComment("settingsConfigurable.service.llama.contextSize.comment")
                            )
                            .addLabeledComponent(
                                CodeGPTBundle.get("settingsConfigurable.service.llama.threads.label"),
                                threadsField
                            )
                            .addComponentToRightColumn(
                                UIUtil.createComment("settingsConfigurable.service.llama.threads.comment")
                            )
                            .addLabeledComponent(
                                CodeGPTBundle.get("settingsConfigurable.service.llama.additionalParameters.label"),
                                additionalParametersField
                            )
                            .addComponentToRightColumn(
                                UIUtil.createComment("settingsConfigurable.service.llama.additionalParameters.comment")
                            )
                            .addLabeledComponent(
                                CodeGPTBundle.get("settingsConfigurable.service.llama.additionalBuildParameters.label"),
                                additionalBuildParametersField
                            )
                            .addComponentToRightColumn(
                                UIUtil.createComment("settingsConfigurable.service.llama.additionalBuildParameters.comment")
                            )
                            .addLabeledComponent(
                                CodeGPTBundle.get("settingsConfigurable.service.llama.additionalEnvironmentVariables.label"),
                                additionalEnvironmentVariablesField
                            )
                            .addComponentToRightColumn(
                                UIUtil.createComment("settingsConfigurable.service.llama.additionalEnvironmentVariables.comment")
                            )
                            .addComponentFillVertically(JPanel(), 0)
                            .panel
                    )
                )
                .panel
        ) as JPanel
    }

    private fun createPortAndButtonsPanel(llamaServerAgent: LlamaServerAgent): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(portField)
            add(Box.createHorizontalStrut(4))

            val serverButton = getServerButton(llamaServerAgent)
            serverButton.maximumSize = serverButton.preferredSize
            add(serverButton)

            add(Box.createHorizontalGlue())
        }
    }

    private fun getServerButton(llamaServerAgent: LlamaServerAgent): JButton {
        val serverRunning = llamaServerAgent.isServerRunning
        val buildInProgress = llamaServerAgent.isBuildInProgress

        return JButton().apply {
            when {
                serverRunning -> {
                    text = CodeGPTBundle.get("settingsConfigurable.service.llama.stopServer.label")
                    icon = AllIcons.Actions.Suspend
                }
                buildInProgress -> {
                    text = CodeGPTBundle.get("llama.ui.button.stopBuild")
                    icon = AllIcons.Actions.Suspend
                }
                else -> {
                    text = CodeGPTBundle.get("settingsConfigurable.service.llama.startServer.label")
                    icon = AllIcons.Actions.Execute
                }
            }

            addActionListener {
                if (!validateModelConfiguration()) {
                    return@addActionListener
                }

                when {
                    llamaServerAgent.isServerRunning -> {
                        enableForm(this)
                        llamaServerAgent.stopAgent()
                    }
                    llamaServerAgent.isBuildInProgress -> {
                        enableForm(this)
                        llamaServerAgent.stopAgent()
                    }
                    else -> {
                        text = CodeGPTBundle.get("llama.ui.button.stopBuild")
                        icon = AllIcons.Actions.Suspend
                        disableForm(this)

                        llamaServerAgent.startAgent(
                            LlamaServerStartupParams(
                                llamaModelPreferencesForm.actualModelPath,
                                contextSize,
                                threads,
                                serverPort,
                                listOfAdditionalParameters,
                                listOfAdditionalBuildParameters,
                                mapOfAdditionalEnvironmentVariables
                            ),
                            parentForm?.let {
                                val strategy = SettingsFormLoggingStrategy(it)
                                strategy.logMessage(CodeGPTBundle.get("llama.debug.buildLoggingStrategy"), false, true)
                                strategy
                            } ?: run {
                                NoOpLoggingStrategy
                            },
                            {
                                setFormEnabled(false)
                                text = CodeGPTBundle.get("settingsConfigurable.service.llama.stopServer.label")
                                icon = AllIcons.Actions.Suspend
                            },
                            {
                                setFormEnabled(true)
                                text = CodeGPTBundle.get("settingsConfigurable.service.llama.startServer.label")
                                icon = AllIcons.Actions.Execute
                            }
                        )
                    }
                }
            }
        }
    }

    private fun validateModelConfiguration(): Boolean {
        return validateCustomModelPath() && validateSelectedModel()
    }

    private fun validateCustomModelPath(): Boolean {
        if (llamaModelPreferencesForm.isUseCustomLlamaModel) {
            val customModelPath = llamaModelPreferencesForm.customLlamaModelPath
            if (customModelPath.isNullOrEmpty()) {
                OverlayUtil.showBalloon(
                    CodeGPTBundle.get("validation.error.fieldRequired"),
                    MessageType.ERROR,
                    llamaModelPreferencesForm.browsableCustomModelTextField
                )
                return false
            }
        }
        return true
    }

    private fun validateSelectedModel(): Boolean {
        if (!llamaModelPreferencesForm.isUseCustomLlamaModel &&
            !LlamaSettings.isModelExists(llamaModelPreferencesForm.selectedModel)
        ) {
            OverlayUtil.showBalloon(
                CodeGPTBundle.get("settingsConfigurable.service.llama.overlay.modelNotDownloaded.text"),
                MessageType.ERROR,
                llamaModelPreferencesForm.huggingFaceModelComboBox
            )
            return false
        }
        return true
    }

    private fun enableForm(serverButton: JButton) {
        setFormEnabled(true)
        serverButton.text = CodeGPTBundle.get("settingsConfigurable.service.llama.startServer.label")
        serverButton.icon = AllIcons.Actions.Execute
    }

    private fun disableForm(serverButton: JButton) {
        setFormEnabled(false)
        serverButton.text = CodeGPTBundle.get("settingsConfigurable.service.llama.stopServer.label")
        serverButton.icon = AllIcons.Actions.Suspend
    }

    private fun setFormEnabled(enabled: Boolean) {
        llamaModelPreferencesForm.enableFields(enabled)
        portField.isEnabled = enabled
        maxTokensField.isEnabled = enabled
        threadsField.isEnabled = enabled
        additionalParametersField.isEnabled = enabled
        additionalBuildParametersField.isEnabled = enabled
        additionalEnvironmentVariablesField.isEnabled = enabled
    }

    val serverPort: Int
        get() = portField.number

    val contextSize: Int
        get() = maxTokensField.value

    var threads: Int
        get() = threadsField.value
        set(value) {
            threadsField.value = value
        }

    val additionalParameters: String
        get() = additionalParametersField.text

    val listOfAdditionalParameters: List<String>
        get() = LlamaSettings.getAdditionalParametersList(additionalParametersField.text)

    val additionalBuildParameters: String
        get() = additionalBuildParametersField.text

    val listOfAdditionalBuildParameters: List<String>
        get() = LlamaSettings.getAdditionalParametersList(additionalBuildParametersField.text)

    val additionalEnvironmentVariables: String
        get() = additionalEnvironmentVariablesField.text

    val mapOfAdditionalEnvironmentVariables: Map<String, String>
        get() = LlamaSettings.getAdditionalEnvironmentVariablesMap(additionalEnvironmentVariablesField.text)

    val promptTemplate: PromptTemplate
        get() = llamaModelPreferencesForm.promptTemplate ?: PromptTemplate.CODE_QWEN
}