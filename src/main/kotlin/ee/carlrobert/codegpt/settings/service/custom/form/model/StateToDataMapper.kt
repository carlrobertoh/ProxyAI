package ee.carlrobert.codegpt.settings.service.custom.form.model

import ee.carlrobert.codegpt.credentials.CredentialsStore
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceCodeCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesState
import java.util.UUID

fun CustomServicesState.mapToData(): CustomServicesStateData =
    CustomServicesStateData(services.map { it.mapToData() })

fun CustomServiceSettingsState.mapToData(): CustomServiceSettingsData =
    CustomServiceSettingsData(
        id = id ?: UUID.randomUUID().toString(),
        name = name,
        template = template,
        apiKey = if (!id.isNullOrEmpty())
            CredentialsStore.getCredential(CredentialsStore.CredentialKey.CustomServiceApiKeyById(id!!))
        else null,
        chatCompletionSettings = chatCompletionSettings.mapToData(),
        codeCompletionSettings = codeCompletionSettings.mapToData()
    )

fun CustomServiceChatCompletionSettingsState.mapToData(): CustomServiceChatCompletionSettingsData =
    CustomServiceChatCompletionSettingsData(
        url = url ?: "",
        headers = headers,
        body = body
    )

fun CustomServiceCodeCompletionSettingsState.mapToData(): CustomServiceCodeCompletionSettingsData =
    CustomServiceCodeCompletionSettingsData(
        codeCompletionsEnabled = codeCompletionsEnabled,
        parseResponseAsChatCompletions = parseResponseAsChatCompletions,
        infillTemplate = infillTemplate,
        url = url ?: "",
        headers = headers,
        body = body
    )
