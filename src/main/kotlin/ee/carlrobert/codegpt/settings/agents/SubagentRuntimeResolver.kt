package ee.carlrobert.codegpt.settings.agents

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.AgentType
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgents
import ee.carlrobert.codegpt.settings.ProxyAISubagent
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType

sealed interface ResolvedSubagentRuntime {
    val displayLabel: String

    data class Native(
        val provider: ServiceType,
        val selection: ModelSelection,
        override val displayLabel: String,
    ) : ResolvedSubagentRuntime

    data class External(
        val externalAgentId: String,
        val displayName: String,
        val configSelections: Map<String, String>,
        override val displayLabel: String,
    ) : ResolvedSubagentRuntime
}

object SubagentRuntimeResolver {

    private const val MODE_OPTION_ID = "mode"
    private val EXTERNAL_RUNTIME_SUMMARY_ORDER = listOf("model", "reasoning_effort")

    fun resolve(
        configuredSubagent: ProxyAISubagent?,
        parentSelection: ModelSelection,
        taskModelOverride: String? = null,
        modelSettings: ModelSettings = ModelSettings.getInstance(),
    ): ResolvedSubagentRuntime {
        configuredSubagent?.runtimeConfigurationError()?.let { throw IllegalArgumentException(it) }

        val externalAgentId = configuredSubagent?.externalAgentId?.takeIf { it.isNotBlank() }
        if (externalAgentId != null) {
            if (!taskModelOverride.isNullOrBlank()) {
                throw IllegalArgumentException(
                    "Task.model overrides are only supported for native subagents."
                )
            }
            val displayName = ExternalAcpAgents.find(externalAgentId)?.displayName ?: externalAgentId
            return ResolvedSubagentRuntime.External(
                externalAgentId = externalAgentId,
                displayName = displayName,
                configSelections = configuredSubagent.externalAgentOptions,
                displayLabel = externalRuntimeLabel(
                    displayName,
                    configuredSubagent.externalAgentOptions
                )
            )
        }

        val provider = configuredSubagent?.provider ?: parentSelection.provider
        val configuredProvider = configuredSubagent?.provider
        val configuredModel = configuredSubagent?.model
        val baseSelection = when {
            configuredProvider != null && !configuredModel.isNullOrBlank() ->
                resolveNativeSelection(
                    modelSettings = modelSettings,
                    provider = configuredProvider,
                    modelCode = configuredModel,
                )

            else -> parentSelection
        }

        val resolvedSelection = taskModelOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { overrideCode ->
                resolveNativeSelection(modelSettings, provider, overrideCode)
            }
            ?: baseSelection

        return ResolvedSubagentRuntime.Native(
            provider = provider,
            selection = resolvedSelection,
            displayLabel = runtimeLabel(provider, resolvedSelection)
        )
    }

    fun resolveForSession(
        project: Project,
        subagentType: String,
        parentProvider: ServiceType?,
        parentModelCode: String?,
        taskModelOverride: String? = null,
        modelSettings: ModelSettings = ModelSettings.getInstance(),
    ): ResolvedSubagentRuntime? {
        val provider = parentProvider ?: return null
        val parentSelection = resolveParentSelection(modelSettings, provider, parentModelCode)
            ?: return null
        val configuredSubagent = findConfiguredSubagent(project, subagentType)
        return runCatching {
            resolve(
                configuredSubagent = configuredSubagent,
                parentSelection = parentSelection,
                taskModelOverride = taskModelOverride,
                modelSettings = modelSettings
            )
        }.getOrNull()
    }

    fun findConfiguredSubagent(project: Project, subagentType: String): ProxyAISubagent? {
        val settingsService = project.service<ProxyAISettingsService>()
        val builtInAgentType = runCatching { AgentType.fromString(subagentType) }.getOrNull()
        if (builtInAgentType != null) {
            val builtInId = SubagentDefaults.builtInIdFor(builtInAgentType) ?: return null
            return settingsService.getSubagents().firstOrNull { it.id == builtInId }
        }

        return settingsService.getSubagents().firstOrNull { subagent ->
            !SubagentDefaults.isBuiltInId(subagent.id) &&
                    subagent.title.equals(subagentType, ignoreCase = true)
        }
    }

    fun runtimeLabel(provider: ServiceType, selection: ModelSelection): String {
        return "${provider.label} · ${selection.displayName}"
    }

    private fun externalRuntimeLabel(
        displayName: String,
        configSelections: Map<String, String>
    ): String {
        if (configSelections.isEmpty()) {
            return displayName
        }

        val remainingSelections = LinkedHashMap(configSelections)
        val orderedSelections = EXTERNAL_RUNTIME_SUMMARY_ORDER.mapNotNull { optionId ->
            remainingSelections.remove(optionId)?.takeIf { it.isNotBlank() }
        }
        val extraSelections = remainingSelections
            .filterKeys { it != MODE_OPTION_ID }
            .values
            .filter { it.isNotBlank() }

        return (listOf(displayName) + orderedSelections + extraSelections)
            .joinToString(" · ")
    }

    fun resolveParentSelection(
        modelSettings: ModelSettings,
        provider: ServiceType,
        modelCode: String?
    ): ModelSelection? {
        val storedCode = modelCode?.takeIf { it.isNotBlank() }
        return when {
            storedCode != null -> modelSettings.findModel(provider, storedCode)
            else -> modelSettings.getModelSelectionForFeature(FeatureType.AGENT)
                .takeIf { it.provider == provider }
                ?: modelSettings.getDefaultModelSelection(FeatureType.AGENT)
                    .takeIf { it.provider == provider }
        }
    }

    private fun resolveNativeSelection(
        modelSettings: ModelSettings,
        provider: ServiceType,
        modelCode: String
    ): ModelSelection {
        return modelSettings.findModel(provider, modelCode)
            ?: throw IllegalArgumentException(
                "Unknown model '$modelCode' for provider ${provider.label}."
            )
    }
}
