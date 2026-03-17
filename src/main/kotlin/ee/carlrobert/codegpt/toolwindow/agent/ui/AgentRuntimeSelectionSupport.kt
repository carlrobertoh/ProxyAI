package ee.carlrobert.codegpt.toolwindow.agent.ui

import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.agent.external.AcpIcons
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgents
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.service.ServiceType
import javax.swing.Icon

object AgentRuntimeSelectionSupport {

    data class PresentationState(
        val icon: Icon?,
        val text: String,
    )

    val cloudProviders = listOf(
        ServiceType.PROXYAI,
        ServiceType.ANTHROPIC,
        ServiceType.OPENAI,
        ServiceType.CUSTOM_OPENAI,
        ServiceType.GOOGLE,
        ServiceType.MISTRAL,
        ServiceType.INCEPTION,
    )

    val offlineProviders = listOf(
        ServiceType.LLAMA_CPP,
        ServiceType.OLLAMA,
    )

    fun compactNativePresentation(
        availableModels: List<ModelSelection>,
        provider: ServiceType,
        modelCode: String?,
    ): PresentationState {
        val selection = findSelection(availableModels, provider, modelCode)
        return PresentationState(
            icon = selection?.icon ?: iconForProvider(provider),
            text = selection?.displayName ?: modelCode?.takeIf { it.isNotBlank() } ?: provider.label
        )
    }

    fun externalPresentation(externalAgentId: String?): PresentationState {
        val safeId = externalAgentId?.takeIf { it.isNotBlank() }
        val preset = safeId?.let(ExternalAcpAgents::find)
        return PresentationState(
            icon = safeId?.let(AcpIcons::iconFor) ?: Icons.DefaultSmall,
            text = preset?.displayName ?: safeId ?: "ACP"
        )
    }

    fun iconForProvider(provider: ServiceType): Icon {
        return when (provider) {
            ServiceType.PROXYAI -> Icons.DefaultSmall
            ServiceType.OPENAI -> Icons.OpenAI
            ServiceType.ANTHROPIC -> Icons.Anthropic
            ServiceType.GOOGLE -> Icons.Google
            ServiceType.MISTRAL -> Icons.Mistral
            ServiceType.OLLAMA -> Icons.Ollama
            ServiceType.CUSTOM_OPENAI -> Icons.OpenAI
            ServiceType.LLAMA_CPP -> Icons.Llama
            ServiceType.INCEPTION -> Icons.Inception
        }
    }

    fun groupLabel(provider: ServiceType): String {
        return if (provider == ServiceType.PROXYAI) "ProxyAI" else provider.label
    }

    fun findSelection(
        availableModels: List<ModelSelection>,
        provider: ServiceType,
        modelCode: String?,
    ): ModelSelection? {
        val code = modelCode?.takeIf { it.isNotBlank() } ?: return null
        return availableModels.firstOrNull { selection ->
            selection.provider == provider &&
                    (selection.selectionId == code || selection.modelId == code)
        }
    }
}
