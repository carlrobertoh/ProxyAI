package ee.carlrobert.codegpt.settings.service.custom.form.model

import ee.carlrobert.codegpt.settings.service.custom.template.CustomServiceTemplate

data class CustomServiceSettingsData(
    val name: String?,
    val template: CustomServiceTemplate,
    val apiKey: String?,
    val chatCompletionSettings: CustomServiceChatCompletionSettingsData,
    val codeCompletionSettings: CustomServiceCodeCompletionSettingsData
)
