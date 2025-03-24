package ee.carlrobert.codegpt.settings.service.custom.form.model

import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceCodeCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState


fun CustomServiceSettingsData.mapToState(): CustomServiceSettingsState =
    CustomServiceSettingsState().also { serviceState ->
        serviceState.name = name
        serviceState.template = template
        serviceState.chatCompletionSettings = chatCompletionSettings.mapToState()
        serviceState.codeCompletionSettings = codeCompletionSettings.mapToState()
    }

fun CustomServiceChatCompletionSettingsData.mapToState(): CustomServiceChatCompletionSettingsState =
    CustomServiceChatCompletionSettingsState().also { serviceState ->
        serviceState.url = url
        serviceState.headers = headers.toMutableMap()
        serviceState.body = body.toMutableMap()
    }

fun CustomServiceCodeCompletionSettingsData.mapToState(): CustomServiceCodeCompletionSettingsState =
    CustomServiceCodeCompletionSettingsState().also { serviceState ->
        serviceState.codeCompletionsEnabled = codeCompletionsEnabled
        serviceState.parseResponseAsChatCompletions = parseResponseAsChatCompletions
        serviceState.infillTemplate = infillTemplate
        serviceState.url = url
        serviceState.headers = headers.toMutableMap()
        serviceState.body = body.toMutableMap()
    }
