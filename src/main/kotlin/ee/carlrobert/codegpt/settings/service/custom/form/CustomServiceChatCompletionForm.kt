package ee.carlrobert.codegpt.settings.service.custom.form

import com.intellij.openapi.ui.MessageType
import com.intellij.util.ui.FormBuilder
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.completions.CompletionRequestService
import ee.carlrobert.codegpt.completions.factory.CustomOpenAIRequestFactory
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceFormTabbedPane
import ee.carlrobert.codegpt.settings.service.custom.form.model.CustomServiceChatCompletionSettingsData
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.ui.URLTextField
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.completion.CompletionEventListener
import okhttp3.sse.EventSource
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class CustomServiceChatCompletionForm(
    state: CustomServiceChatCompletionSettingsData,
    val getApiKey: () -> String?
) {

    private val urlField = URLTextField(state.url, 30)
    private val tabbedPane = CustomServiceFormTabbedPane(state.headers, state.body)
    private val testConnectionButton = JButton(
        CodeGPTBundle.get("settingsConfigurable.service.custom.openai.testConnection.label")
    )

    init {
        testConnectionButton.addActionListener {
            testConnection()
        }
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
            .addLabeledComponent(
                CodeGPTBundle.get("settingsConfigurable.service.custom.openai.url.label"),
                JPanel(BorderLayout(8, 0)).apply {
                    add(urlField, BorderLayout.CENTER)
                    add(testConnectionButton, BorderLayout.EAST)
                }
            )
            .addComponent(tabbedPane)
            .addComponentFillVertically(JPanel(), 0)
            .panel

    fun resetForm(settings: CustomServiceChatCompletionSettingsState) {
        urlField.text = settings.url
        tabbedPane.headers = settings.headers
        tabbedPane.body = settings.body
    }

    private fun testConnection() {
        testConnectionButton.isEnabled = false
        testConnectionButton.text = "Testing..."

        val request = CustomOpenAIRequestFactory.buildCustomOpenAICompletionRequest(
            "Test",
            urlField.text,
            tabbedPane.headers,
            tabbedPane.body,
            getApiKey.invoke()

        )

        CompletionRequestService.getInstance().getCustomOpenAIChatCompletionAsync(
            request,
            TestConnectionEventListener()
        )
    }

    internal inner class TestConnectionEventListener : CompletionEventListener<String?> {
        private var responseReceived = false

        override fun onMessage(value: String?, eventSource: EventSource) {
            if (!responseReceived) {
                responseReceived = true
                testConnectionButton.isEnabled = true
                testConnectionButton.text =
                    CodeGPTBundle.get("settingsConfigurable.service.custom.openai.testConnection.label")
                OverlayUtil.showBalloon(
                    "Connection successful!",
                    MessageType.INFO,
                    testConnectionButton
                )
                eventSource.cancel()
            }
        }

        override fun onError(error: ErrorDetails, ex: Throwable) {
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
}