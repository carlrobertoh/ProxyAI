package ee.carlrobert.codegpt.settings.service.custom.form.model

data class CustomServicesStateData(
    val services: List<CustomServiceSettingsData>,
    val active: CustomServiceSettingsData
)
