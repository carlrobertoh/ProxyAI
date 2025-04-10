package ee.carlrobert.codegpt.settings.service.ollama

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.observable.util.whenTextChangedFromUi
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageType
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.OllamaApikey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.settings.service.CodeCompletionConfigurationForm
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.ui.UIUtil
import ee.carlrobert.codegpt.ui.URLTextField
import ee.carlrobert.llm.client.ollama.OllamaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.lang.String.format
import java.net.ConnectException
import java.util.concurrent.TimeoutException
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel

class OllamaSettingsForm {

    private val refreshModelsButton =
        JButton(CodeGPTBundle.get("settingsConfigurable.service.ollama.models.refresh"))
    private val hostField: JBTextField
    private val modelComboBox: ComboBox<String>
    private val codeCompletionConfigurationForm: CodeCompletionConfigurationForm
    private val apiKeyField: JBPasswordField

    companion object {
        private val logger = thisLogger()
    }

    init {
        val settings = service<OllamaSettings>().state
        codeCompletionConfigurationForm = CodeCompletionConfigurationForm(
            settings.codeCompletionsEnabled,
            settings.fimOverride,
            settings.fimTemplate
        )
        val emptyModelsComboBoxModel =
            DefaultComboBoxModel(arrayOf("Hit refresh to see models for this host"))
        modelComboBox = ComboBox(emptyModelsComboBoxModel).apply {
            isEnabled = false
        }
        hostField = URLTextField().apply {
            text = settings.host
            whenTextChangedFromUi {
                modelComboBox.model = emptyModelsComboBoxModel
                modelComboBox.isEnabled = false
            }
        }
        refreshModelsButton.addActionListener {
            refreshModels(getModel() ?: settings.model)
        }
        apiKeyField = JBPasswordField().apply {
            columns = 30
            text = runBlocking(Dispatchers.IO) {
                getCredential(OllamaApikey)
            }
        }
    }

    fun getForm(): JPanel = FormBuilder.createFormBuilder()
        .addComponent(TitledSeparator(CodeGPTBundle.get("shared.configuration")))
        .addComponent(
            FormBuilder.createFormBuilder()
                .setFormLeftIndent(16)
                .addLabeledComponent(
                    CodeGPTBundle.get("settingsConfigurable.shared.baseHost.label"),
                    hostField
                )
                .addLabeledComponent(
                    CodeGPTBundle.get("settingsConfigurable.shared.model.label"),
                    JPanel(BorderLayout(8, 0)).apply {
                        add(modelComboBox, BorderLayout.CENTER)
                        add(refreshModelsButton, BorderLayout.EAST)
                    }
                )
                .addComponent(TitledSeparator(CodeGPTBundle.get("settingsConfigurable.shared.authentication.title")))
                .setFormLeftIndent(32)
                .addLabeledComponent(
                    CodeGPTBundle.get("settingsConfigurable.shared.apiKey.label"),
                    apiKeyField
                )
                .addComponentToRightColumn(UIUtil.createComment("settingsConfigurable.shared.apiKey.comment"))
                .panel
        )
        .addComponent(TitledSeparator(CodeGPTBundle.get("shared.codeCompletions")))
        .addComponent(UIUtil.withEmptyLeftBorder(codeCompletionConfigurationForm.getForm()))
        .addComponentFillVertically(JPanel(), 0)
        .panel

    fun getModel(): String? {
        return if (modelComboBox.isEnabled) {
            modelComboBox.item
        } else {
            null
        }
    }

    fun getApiKey(): String? = String(apiKeyField.password).ifEmpty { null }

    fun resetForm() {
        service<OllamaSettings>().state.run {
            hostField.text = host
            modelComboBox.item = model ?: ""
            codeCompletionConfigurationForm.isCodeCompletionsEnabled = codeCompletionsEnabled
            codeCompletionConfigurationForm.fimTemplate = fimTemplate
            codeCompletionConfigurationForm.fimOverride != fimOverride
        }
        apiKeyField.text = getCredential(OllamaApikey)
    }

    fun applyChanges() {
        service<OllamaSettings>().state.run {
            host = hostField.text
            model = modelComboBox.item
            codeCompletionsEnabled = codeCompletionConfigurationForm.isCodeCompletionsEnabled
            fimTemplate = codeCompletionConfigurationForm.fimTemplate!!
            fimOverride = codeCompletionConfigurationForm.fimOverride ?: false
        }
        setCredential(OllamaApikey, getApiKey())
    }

    fun isModified() = service<OllamaSettings>().state.run {
        hostField.text != host
                || (modelComboBox.item != model && modelComboBox.isEnabled)
                || codeCompletionConfigurationForm.isCodeCompletionsEnabled != codeCompletionsEnabled
                || codeCompletionConfigurationForm.fimTemplate != fimTemplate
                || codeCompletionConfigurationForm.fimOverride != fimOverride
                || getApiKey() != getCredential(OllamaApikey)
    }

    private fun refreshModels(currentModel: String?) {
        disableModelComboBoxWithPlaceholder(DefaultComboBoxModel(arrayOf("Loading")))
        ReadAction.nonBlocking<List<String>> {
            try {
                OllamaClient.Builder()
                    .setHost(hostField.text)
                    .setApiKey(getApiKey())
                    .build()
                    .modelTags
                    .models
                    ?.map { it.name }
                    ?.sortedWith(compareBy({ it.split(":").first() }, {
                        if (it.contains("latest")) 1 else 0
                    }))
            } catch (t: Throwable) {
                handleModelLoadingError(t)
                throw t
            }
        }
            .finishOnUiThread(ModalityState.defaultModalityState()) { models ->
                updateModelComboBoxState(models, currentModel)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun updateModelComboBoxState(models: List<String>, currentModel: String?) {
        if (models.isNotEmpty()) {
            modelComboBox.model = DefaultComboBoxModel(models.toTypedArray())
            modelComboBox.isEnabled = true
            currentModel?.let {
                if (models.contains(currentModel)) {
                    modelComboBox.selectedItem = currentModel
                } else {
                    OverlayUtil.showBalloon(
                        format(
                            CodeGPTBundle.get("validation.error.model.notExists"),
                            currentModel
                        ),
                        MessageType.ERROR,
                        modelComboBox
                    )
                }
            }
        } else {
            modelComboBox.model = DefaultComboBoxModel(arrayOf("No models"))
        }
        val availableModels = ApplicationManager.getApplication()
            .getService(OllamaSettings::class.java)
            .state.availableModels
        availableModels.removeAll { !models.contains(it) }
        models.forEach { model ->
            if (!availableModels.contains(model)) {
                availableModels.add(model)
            }
        }
        availableModels.sortWith(
            compareBy({ it.split(":").first() }, {
                if (it.contains("latest")) 1 else 0
            })
        )
    }

    private fun handleModelLoadingError(ex: Throwable) {
        logger.error(ex)
        when (ex) {
            is TimeoutException -> OverlayUtil.showNotification(
                "Connection to Ollama server timed out",
                NotificationType.ERROR
            )

            is ConnectException -> OverlayUtil.showNotification(
                "Unable to connect to Ollama server",
                NotificationType.ERROR
            )

            else -> OverlayUtil.showNotification(ex.message ?: "Error", NotificationType.ERROR)
        }
        disableModelComboBoxWithPlaceholder(DefaultComboBoxModel(arrayOf("Unable to load models")))
    }

    private fun disableModelComboBoxWithPlaceholder(placeholderModel: ComboBoxModel<String>) {
        modelComboBox.apply {
            model = placeholderModel
            isEnabled = false
        }
    }
}
