package ee.carlrobert.codegpt.settings.service.custom.form.model

import ee.carlrobert.codegpt.credentials.CredentialsStore
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceCodeCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesState

fun CustomServicesState.mapToData(): CustomServicesStateData =
    CustomServicesStateData(
        services = services.map { it.mapToData() },
        active = active.mapToData()
    )

fun CustomServiceSettingsState.mapToData(): CustomServiceSettingsData =
    CustomServiceSettingsData(
        name = name,
        template = template,
        apiKey = CredentialsStore.getCredential(CredentialsStore.CredentialKey.CustomServiceApiKey(name.orEmpty())),
        chatCompletionSettings = chatCompletionSettings.mapToData(),
        codeCompletionSettings = codeCompletionSettings.mapToData()
    )

fun CustomServiceChatCompletionSettingsState.mapToData(): CustomServiceChatCompletionSettingsData =
    CustomServiceChatCompletionSettingsData(
        url = url,
        headers = headers,
        body = body
    )

fun CustomServiceCodeCompletionSettingsState.mapToData(): CustomServiceCodeCompletionSettingsData =
    CustomServiceCodeCompletionSettingsData(
        codeCompletionsEnabled = codeCompletionsEnabled,
        parseResponseAsChatCompletions = parseResponseAsChatCompletions,
        infillTemplate = infillTemplate,
        url = url,
        headers = headers,
        body = body
    )