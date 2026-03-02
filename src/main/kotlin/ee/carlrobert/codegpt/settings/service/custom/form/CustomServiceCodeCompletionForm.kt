package ee.carlrobert.codegpt.settings.service.custom.form

import com.intellij.icons.AllIcons.General
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.panel.ComponentPanelBuilder
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.codecompletions.CodeCompletionService
import ee.carlrobert.codegpt.codecompletions.InfillPromptTemplate
import ee.carlrobert.codegpt.codecompletions.InfillRequest
import ee.carlrobert.codegpt.completions.CancellableRequest
import ee.carlrobert.codegpt.completions.CompletionError
import ee.carlrobert.codegpt.completions.CompletionStreamEventListener
import ee.carlrobert.codegpt.settings.Placeholder
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceCodeCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceFormTabbedPane
import ee.carlrobert.codegpt.settings.service.custom.form.model.CustomServiceCodeCompletionSettingsData
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.ui.URLTextField
import org.apache.commons.text.StringEscapeUtils
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JPanel

class CustomServiceCodeCompletionForm(
    state: CustomServiceCodeCompletionSettingsData,
    val getApiKey: () -> String?
) {

    private val featureEnabledCheckBox = JBCheckBox(
        CodeGPTBundle.get("codeCompletionsForm.enableFeatureText"),
        state.codeCompletionsEnabled
    )
    private val parseResponseAsChatCompletionsCheckBox = JBCheckBox(
        CodeGPTBundle.get("codeCompletionsForm.parseResponseAsChatCompletions"),
        state.parseResponseAsChatCompletions
    )
    private val promptTemplateComboBox =
        ComboBox(EnumComboBoxModel(InfillPromptTemplate::class.java)).apply {
            selectedItem = state.infillTemplate
            setSelectedItem(InfillPromptTemplate.CODE_LLAMA)
            addItemListener {
                updatePromptTemplateHelpTooltip(it.item as InfillPromptTemplate)
            }
        }
    private val promptTemplateHelpText = JBLabel(General.ContextHelp)
    private val urlField = URLTextField(state.url, 30)
    private val tabbedPane = CustomServiceFormTabbedPane(state.headers, state.body)
    private val testConnectionButton = JButton(
        CodeGPTBundle.get("settingsConfigurable.service.custom.openai.testConnection.label")
    )

    init {
        testConnectionButton.addActionListener { testConnection() }
        updatePromptTemplateHelpTooltip(state.infillTemplate)
    }

    var codeCompletionsEnabled: Boolean
        get() = featureEnabledCheckBox.isSelected
        set(enabled) {
            featureEnabledCheckBox.isSelected = enabled
        }

    var parseResponseAsChatCompletions: Boolean
        get() = parseResponseAsChatCompletionsCheckBox.isSelected
        set(enabled) {
            parseResponseAsChatCompletionsCheckBox.isSelected = enabled
        }

    var infillTemplate: InfillPromptTemplate
        get() = promptTemplateComboBox.item
        set(template) {
            promptTemplateComboBox.selectedItem = template
        }

    var url: String
        get() = urlField.text
        set(url) {
            urlField.text = url
        }

    var headers: MutableMap<String, String>
        get() = tabbedPane.headers
        set(value) {
            tabbedPane.headers = value
        }

    var body: MutableMap<String, Any>
        get() = tabbedPane.body
        set(value) {
            tabbedPane.body = value
        }

    val form: JPanel
        get() = FormBuilder.createFormBuilder()
            .addVerticalGap(8)
            .addComponent(featureEnabledCheckBox)
            .addVerticalGap(4)
            .addComponent(parseResponseAsChatCompletionsCheckBox)
            .addVerticalGap(4)
            .addLabeledComponent(
                "FIM template:",
                JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                    add(promptTemplateComboBox)
                    add(Box.createHorizontalStrut(4))
                    add(promptTemplateHelpText)
                })
            .addLabeledComponent(
                CodeGPTBundle.get("settingsConfigurable.service.custom.openai.url.label"),
                JPanel(BorderLayout(8, 0)).apply {
                    add(urlField, BorderLayout.CENTER)
                    add(testConnectionButton, BorderLayout.EAST)
                }
            )
            .addComponent(tabbedPane)
            .addComponent(
                ComponentPanelBuilder.createCommentComponent(
                    getHtmlDescription(),
                    true,
                    100
                )
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

    private fun getHtmlDescription(): String {
        val placeholderDescriptions = listOf(
            Placeholder.FIM_PROMPT,
            Placeholder.PREFIX,
            Placeholder.SUFFIX
        ).joinToString("\n") {
            "<li><strong>\$${it.name}</strong>: ${it.description}</li>"
        }

        return buildString {
            append("<html>\n")
            append("<body>\n")
            append("<p>Use the following placeholders to insert dynamic values:</p>\n")
            append("<ul>$placeholderDescriptions</ul>\n")
            append("</body>\n")
            append("</html>")
        }
    }

    fun resetForm(settings: CustomServiceCodeCompletionSettingsState) {
        featureEnabledCheckBox.isSelected = settings.codeCompletionsEnabled
        parseResponseAsChatCompletionsCheckBox.isSelected = settings.parseResponseAsChatCompletions
        promptTemplateComboBox.selectedItem = settings.infillTemplate
        urlField.text = settings.url
        tabbedPane.headers = settings.headers
        tabbedPane.body = settings.body
        updatePromptTemplateHelpTooltip(settings.infillTemplate)
    }

    private fun testConnection() {
        testConnectionButton.isEnabled = false
        testConnectionButton.text = "Testing..."

        val settings = CustomServiceCodeCompletionSettingsState().apply {
            codeCompletionsEnabled = true
            parseResponseAsChatCompletions = this@CustomServiceCodeCompletionForm.parseResponseAsChatCompletions
            infillTemplate = this@CustomServiceCodeCompletionForm.infillTemplate
            url = urlField.text
            headers.clear()
            headers.putAll(tabbedPane.headers)
            body.clear()
            body.putAll(tabbedPane.body)
        }

        val listener = TestConnectionEventListener()
        val request = service<CodeCompletionService>().testCustomOpenAIConnectionAsync(
            settings = settings,
            apiKey = getApiKey.invoke(),
            eventListener = listener
        )
        listener.attachRequest(request)
    }


    internal inner class TestConnectionEventListener : CompletionStreamEventListener {
        private var responseReceived = false
        private var request: CancellableRequest? = null

        fun attachRequest(request: CancellableRequest) {
            this.request = request
        }

        override fun onMessage(message: String) {
            if (!responseReceived && message.isNotBlank()) {
                responseReceived = true
                testConnectionButton.isEnabled = true
                testConnectionButton.text =
                    CodeGPTBundle.get("settingsConfigurable.service.custom.openai.testConnection.label")
                OverlayUtil.showBalloon(
                    "Connection successful!",
                    MessageType.INFO,
                    testConnectionButton
                )
                request?.cancel()
            }
        }

        override fun onError(error: CompletionError, ex: Throwable) {
            testConnectionButton.isEnabled = true
            testConnectionButton.text =
                CodeGPTBundle.get("settingsConfigurable.service.custom.openai.testConnection.label")
            OverlayUtil.showBalloon(
                "Connection failed: ${error.message}",
                MessageType.ERROR,
                testConnectionButton
            )
        }

        override fun onComplete(messageBuilder: StringBuilder) {
            if (!responseReceived) {
                testConnectionButton.isEnabled = true
                testConnectionButton.text =
                    CodeGPTBundle.get("settingsConfigurable.service.custom.openai.testConnection.label")
                OverlayUtil.showBalloon(
                    "Connection successful!",
                    MessageType.INFO,
                    testConnectionButton
                )
            }
        }

        override fun onCancelled(messageBuilder: StringBuilder) {
            testConnectionButton.isEnabled = true
            testConnectionButton.text =
                CodeGPTBundle.get("settingsConfigurable.service.custom.openai.testConnection.label")
        }
    }

    private fun updatePromptTemplateHelpTooltip(template: InfillPromptTemplate) {
        promptTemplateHelpText.setToolTipText(null)

        val description = StringEscapeUtils.escapeHtml4(
            template.buildPrompt(
                InfillRequest.Builder("PREFIX", "SUFFIX", 0).build(),
            )
        )
        HelpTooltip()
            .setTitle(template.toString())
            .setDescription("<html><p>$description</p></html>")
            .installOn(promptTemplateHelpText)
    }
}
