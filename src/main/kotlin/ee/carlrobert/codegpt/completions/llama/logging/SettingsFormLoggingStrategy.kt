package ee.carlrobert.codegpt.completions.llama.logging

import ee.carlrobert.codegpt.settings.service.llama.form.LlamaSettingsForm

class SettingsFormLoggingStrategy(
    private val settingsForm: LlamaSettingsForm
) : ServerLoggingStrategy {

    override fun logMessage(message: String, isError: Boolean, isBuildLog: Boolean) {
        settingsForm.logToConsole(message, isError, isBuildLog)
    }

    override fun setPhase(phase: String) {
        settingsForm.updateServerStatusWithPhase(phase)
    }

    override fun startProgress() {
        settingsForm.updateServerStatusWithPhase("Initializing...")
    }

    override fun stopProgress() {
        settingsForm.refreshServerStatus()
    }
}