package ee.carlrobert.codegpt.settings.service.custom.form.model

import ee.carlrobert.codegpt.codecompletions.InfillPromptTemplate

data class CustomServiceCodeCompletionSettingsData(
    val codeCompletionsEnabled: Boolean,
    val parseResponseAsChatCompletions: Boolean,
    val infillTemplate: InfillPromptTemplate,
    val url: String?,
    val headers: Map<String, String>,
    val body: Map<String, Any>
)
