package ee.carlrobert.codegpt.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import ee.carlrobert.codegpt.codecompletions.CodeCompletionService
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ModelRole.CODECOMPLETION_ROLE
import ee.carlrobert.codegpt.settings.service.ServiceType.*
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings

abstract class CodeCompletionFeatureToggleActions(
    private val enableFeatureAction: Boolean
) : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) = when (GeneralSettings.getSelectedService(CODECOMPLETION_ROLE)) {
        CODEGPT -> service<CodeGPTServiceSettings>().state.codeCompletionSettings::codeCompletionsEnabled::set

        OPENAI -> OpenAISettings.getCurrentState()::setCodeCompletionsEnabled

        LLAMA_CPP -> LlamaSettings.getCurrentState()::setCodeCompletionsEnabled

        OLLAMA -> service<OllamaSettings>().state::codeCompletionsEnabled::set

        CUSTOM_OPENAI -> service<CustomServicesSettings>().state.active.codeCompletionSettings::codeCompletionsEnabled::set

        ANTHROPIC,
        GOOGLE,
        null -> { _: Boolean -> Unit } // no-op for these services
    }(enableFeatureAction)

    override fun update(e: AnActionEvent) {
        val selectedService = GeneralSettings.getSelectedService(CODECOMPLETION_ROLE)
        val codeCompletionEnabled =
            e.project?.service<CodeCompletionService>()?.isCodeCompletionsEnabled(selectedService)
                ?: false
        e.presentation.isVisible = codeCompletionEnabled != enableFeatureAction
        e.presentation.isEnabled = when (selectedService) {
            CODEGPT,
            OPENAI,
            CUSTOM_OPENAI,
            LLAMA_CPP,
            OLLAMA -> true

            ANTHROPIC,
            GOOGLE,
            null -> false
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

class EnableCompletionsAction : CodeCompletionFeatureToggleActions(true)

class DisableCompletionsAction : CodeCompletionFeatureToggleActions(false)