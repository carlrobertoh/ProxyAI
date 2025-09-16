package ee.carlrobert.codegpt.settings.service.custom.form.model

import ee.carlrobert.codegpt.settings.service.custom.template.CustomServiceTemplate
import java.util.UUID

data class CustomServiceSettingsData(
    val id: String = UUID.randomUUID().toString(),
    val name: String?,
    val template: CustomServiceTemplate,
    val apiKey: String?,
    val chatCompletionSettings: CustomServiceChatCompletionSettingsData,
    val codeCompletionSettings: CustomServiceCodeCompletionSettingsData
)
