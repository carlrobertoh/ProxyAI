package ee.carlrobert.codegpt.settings.service

import ee.carlrobert.codegpt.settings.service.ModelRole.*
import com.intellij.ide.DataManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceConfigurable
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceForm
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceConfigurable
import ee.carlrobert.codegpt.settings.service.google.GoogleSettingsConfigurable
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettingsConfigurable
import javax.swing.JPanel

class ServiceConfigurableComponent {

    var form: CodeGPTServiceForm = CodeGPTServiceForm()

    private var serviceComboBox: ComboBox<ServiceType> =
        ComboBox(EnumComboBoxModel(ServiceType::class.java)).apply {
            selectedItem = service<GeneralSettings>().state.getSelectedService(CHAT_ROLE)
        }
    private var codeCompletionServiceComboBox: ComboBox<ServiceType> =
        ComboBox(EnumComboBoxModel(ServiceType::class.java)).apply {
            selectedItem = service<GeneralSettings>().state.getSelectedService(CODECOMPLETION_ROLE)
        }

    fun getSelectedService(role: ModelRole): ServiceType {
        return when(role) {
            CHAT_ROLE -> serviceComboBox
            CODECOMPLETION_ROLE -> codeCompletionServiceComboBox
        }.selectedItem as ServiceType
    }

    fun setSelectedService(role: ModelRole, serviceType: ServiceType) {
        when(role) {
            CHAT_ROLE -> serviceComboBox
            CODECOMPLETION_ROLE -> codeCompletionServiceComboBox
        }.selectedItem = serviceType
    }

    fun getPanel(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.label"),
            serviceComboBox
        )
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.codeCompletion.label"),
            codeCompletionServiceComboBox
        )
        .addVerticalGap(8)
        .addComponent(JBLabel("All available providers that can be used with CodeGPT:"))
        .addVerticalGap(8)
        .addComponent(FormBuilder.createFormBuilder()
            .setFormLeftIndent(20).apply {
                addLinks(this)
            }
            .panel)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    private fun addLinks(formBuilder: FormBuilder) {
        mapOf(
            "ProxyAI" to CodeGPTServiceConfigurable::class.java,
            "OpenAI" to OpenAIServiceConfigurable::class.java,
            "Custom OpenAI" to CustomServiceConfigurable::class.java,
            "Azure" to AzureServiceConfigurable::class.java,
            "Anthropic" to AnthropicServiceConfigurable::class.java,
            "Google" to GoogleSettingsConfigurable::class.java,
            "LLaMA C/C++ (Local)" to LlamaServiceConfigurable::class.java,
            "Ollama (Local)" to OllamaSettingsConfigurable::class.java,
        ).entries.forEach { (name, configurableClass) ->
            formBuilder.addComponent(ActionLink(name) {
                val context = service<DataManager>().getDataContext(it.source as ActionLink)
                val settings = Settings.KEY.getData(context)
                settings?.select(settings.find(configurableClass))
            })
        }
    }
}