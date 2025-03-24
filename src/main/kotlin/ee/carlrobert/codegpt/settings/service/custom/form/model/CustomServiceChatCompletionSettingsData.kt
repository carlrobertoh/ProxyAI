package ee.carlrobert.codegpt.settings.service.custom.form.model

data class CustomServiceChatCompletionSettingsData(
    val url: String?,
    val headers: Map<String, String>,
    val body: Map<String, Any>
)
