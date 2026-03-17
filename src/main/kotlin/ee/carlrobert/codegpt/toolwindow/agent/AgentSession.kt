package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.settings.service.ServiceType

data class AcpConfigOptionChoice(
    val value: String,
    val name: String,
    val description: String? = null
)

data class AcpConfigOption(
    val id: String,
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val type: String? = null,
    val currentValue: String? = null,
    val options: List<AcpConfigOptionChoice> = emptyList()
)

object AcpConfigOptions {
    fun selectable(options: List<AcpConfigOption>): List<AcpConfigOption> {
        return options
            .asSequence()
            .filter { it.type == "select" && it.options.isNotEmpty() }
            .sortedBy { categoryOrder(it.category) }
            .toList()
    }

    fun label(option: AcpConfigOption): String {
        return when (option.category.orEmpty()) {
            "model" -> "Model"
            "mode" -> "Mode"
            "thought_level" -> "Reasoning"
            else -> option.name
        }
    }

    fun selectedValueName(options: List<AcpConfigOption>, category: String): String? {
        val option = selectable(options).firstOrNull { it.category == category } ?: return null
        return option.options
            .firstOrNull { it.value == option.currentValue }
            ?.name
            ?: option.currentValue
    }

    fun selectedChoice(
        option: AcpConfigOption,
        selections: Map<String, String>
    ): AcpConfigOptionChoice? {
        return option.options.firstOrNull { it.value == selections[option.id] }
            ?: option.options.firstOrNull { it.value == option.currentValue }
            ?: option.options.firstOrNull()
    }

    fun normalizeSelections(
        options: List<AcpConfigOption>,
        selections: Map<String, String>
    ): Map<String, String> {
        val selectableById = selectable(options).associateBy { it.id }
        return selections
            .mapNotNull { (optionId, value) ->
                val option = selectableById[optionId] ?: return@mapNotNull null
                if (option.options.any { it.value == value }) {
                    optionId to value
                } else {
                    null
                }
            }
            .toMap(linkedMapOf())
    }

    private fun categoryOrder(category: String?): Int {
        return when (category.orEmpty()) {
            "model" -> 0
            "mode" -> 1
            "thought_level" -> 2
            else -> 3
        }
    }
}

/**
 * Represents a single Agent session with its own conversation state and metadata.
 * Each tab in the Agent tool window corresponds to one AgentSession.
 */
data class AgentSession(
    val sessionId: String,
    val conversation: Conversation,
    var displayName: String = "",
    var serviceType: ServiceType? = null,
    var modelCode: String? = null,
    var externalAgentId: String? = null,
    var externalAgentSessionId: String? = null,
    var externalAgentConfigOptions: List<AcpConfigOption> = emptyList(),
    var externalAgentConfigSelections: Map<String, String> = emptyMap(),
    var externalAgentConfigLoading: Boolean = false,
    var runtimeAgentId: String? = null,
    var resumeCheckpointRef: CheckpointRef? = null,
    val referencedFiles: List<VirtualFile> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    var lastActiveAt: Long = System.currentTimeMillis()
) {
    var externalAgentErrorMessage: String? = null
}
