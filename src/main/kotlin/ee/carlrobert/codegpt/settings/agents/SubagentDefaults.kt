package ee.carlrobert.codegpt.settings.agents

import ee.carlrobert.codegpt.agent.AgentType
import ee.carlrobert.codegpt.agent.SubagentTool
import ee.carlrobert.codegpt.settings.ProxyAISubagent

object SubagentDefaults {
    const val EXPLORER_ID = 1
    const val GENERAL_ID = 2

    fun defaults(): List<ProxyAISubagent> {
        val explorerTools = SubagentTool.toStoredValues(SubagentTool.readOnly + SubagentTool.BASH)
        val generalTools = SubagentTool.toStoredValues(SubagentTool.entries)
        return listOf(
            ProxyAISubagent(
                id = EXPLORER_ID,
                title = "Explorer",
                objective = "Read-only subagent for codebase exploration and insight gathering.",
                tools = explorerTools
            ),
            ProxyAISubagent(
                id = GENERAL_ID,
                title = "General Task",
                objective = "General-purpose subagent for executing concrete coding tasks.",
                tools = generalTools
            )
        )
    }

    fun ensureBuiltIns(subagents: List<ProxyAISubagent>): List<ProxyAISubagent> {
        val defaults = defaults()
        val byId = subagents.associateBy { it.id }
        val explorer = byId[EXPLORER_ID] ?: defaults.first { it.id == EXPLORER_ID }
        val general = byId[GENERAL_ID] ?: defaults.first { it.id == GENERAL_ID }
        val custom = subagents.filterNot { isBuiltInId(it.id) }
        return listOf(explorer, general) + custom
    }

    fun isBuiltInId(id: Int): Boolean = id == EXPLORER_ID || id == GENERAL_ID

    fun builtInIdFor(agentType: AgentType): Int? {
        return when (agentType) {
            AgentType.GENERAL_PURPOSE -> GENERAL_ID
            AgentType.EXPLORE -> EXPLORER_ID
        }
    }
}
