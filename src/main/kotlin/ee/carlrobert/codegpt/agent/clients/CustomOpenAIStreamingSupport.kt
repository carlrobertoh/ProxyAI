package ee.carlrobert.codegpt.agent.clients

import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings

internal fun shouldStreamCustomOpenAI(featureType: FeatureType): Boolean {
    return runCatching {
        service<CustomServicesSettings>()
            .customServiceStateForFeatureType(featureType)
            .chatCompletionSettings
            .shouldStream()
    }.getOrDefault(false)
}
