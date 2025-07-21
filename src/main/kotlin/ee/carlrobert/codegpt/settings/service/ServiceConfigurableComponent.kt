package ee.carlrobert.codegpt.settings.service

import com.intellij.ide.DataManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ex.Settings
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceConfigurable
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceForm
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceConfigurable
import ee.carlrobert.codegpt.settings.service.google.GoogleSettingsConfigurable
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettingsConfigurable
import javax.swing.JPanel

class ServiceConfigurableComponent {

    var form: CodeGPTServiceForm = CodeGPTServiceForm()

    fun getPanel(): JPanel = FormBuilder.createFormBuilder()
        .addComponent(JBLabel("All available providers that can be used with ProxyAI:"))
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
            "Anthropic" to AnthropicServiceConfigurable::class.java,
            "Google" to GoogleSettingsConfigurable::class.java,
            "Mistral" to MistralServiceConfigurable::class.java,
            "LLaMA C/C++" to LlamaServiceConfigurable::class.java,
            "Ollama" to OllamaSettingsConfigurable::class.java,
        ).entries.forEach { (name, configurableClass) ->
            formBuilder.addComponent(ActionLink(name) {
                val context = service<DataManager>().getDataContext(it.source as ActionLink)
                val settings = Settings.KEY.getData(context)
                settings?.select(settings.find(configurableClass))
            })
        }
    }
}