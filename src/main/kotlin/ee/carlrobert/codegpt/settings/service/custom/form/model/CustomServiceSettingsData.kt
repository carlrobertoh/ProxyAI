package ee.carlrobert.codegpt.settings.service.custom.form.model

import ee.carlrobert.codegpt.settings.service.custom.DEFAULT_CUSTOM_OPENAI_CONTEXT_WINDOW_SIZE
import ee.carlrobert.codegpt.settings.service.custom.DEFAULT_CUSTOM_OPENAI_MAX_OUTPUT_TOKENS
import ee.carlrobert.codegpt.settings.service.custom.template.CustomServiceTemplate
import java.util.UUID

data class CustomServiceSettingsData(
    val id: String = UUID.randomUUID().toString(),
    val name: String?,
    val template: CustomServiceTemplate,
    val apiKey: String?,
    val contextWindowSize: Long = DEFAULT_CUSTOM_OPENAI_CONTEXT_WINDOW_SIZE,
    val maxOutputTokens: Long = DEFAULT_CUSTOM_OPENAI_MAX_OUTPUT_TOKENS,
    val chatCompletionSettings: CustomServiceChatCompletionSettingsData,
    val codeCompletionSettings: CustomServiceCodeCompletionSettingsData
)
