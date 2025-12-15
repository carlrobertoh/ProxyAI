package ee.carlrobert.codegpt.agent

/**
 * Represents the different types of specialized agents that can be launched via the Task tool.
 * Each agent type has a specific focus and optimized tool set for its intended use case.
 */
sealed class AgentType(val value: String, val description: String) {

    object GENERAL_PURPOSE : AgentType(
        value = "general-purpose",
        description = "General-purpose agent for complex research, code search, and multi-step tasks"
    )

    object EXPLORE : AgentType(
        value = "explore",
        description = "Fast agent specialized for exploring codebases and finding files/patterns"
    )

    companion object {
        fun fromString(value: String): AgentType = when (value.lowercase().trim()) {
            "general-purpose" -> GENERAL_PURPOSE
            "explore" -> EXPLORE
            else -> throw IllegalArgumentException(
                "Unknown agent type: '$value'. Valid types are: general-purpose, explore, plan."
            )
        }
    }
}