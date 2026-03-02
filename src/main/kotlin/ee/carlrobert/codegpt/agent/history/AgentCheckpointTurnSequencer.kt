package ee.carlrobert.codegpt.agent.history

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import ai.koog.prompt.message.Message as PromptMessage

object AgentCheckpointTurnSequencer {

    data class Turn(
        val prompt: String,
        val userNonSystemMessageCount: Int,
        val events: List<TurnEvent>
    )

    sealed interface TurnEvent {
        val nonSystemMessageCount: Int

        data class Assistant(
            val content: String,
            override val nonSystemMessageCount: Int
        ) : TurnEvent

        data class Reasoning(
            val content: String,
            override val nonSystemMessageCount: Int
        ) : TurnEvent

        data class ToolCall(
            val id: String?,
            val tool: String,
            val content: String,
            override val nonSystemMessageCount: Int
        ) : TurnEvent

        data class ToolResult(
            val id: String?,
            val tool: String,
            val content: String,
            override val nonSystemMessageCount: Int
        ) : TurnEvent
    }

    fun toVisibleTurns(
        history: List<PromptMessage>,
        projectInstructions: String?,
        preserveSyntheticContinuation: Boolean = false
    ): List<Turn> {
        val turns = mutableListOf<Turn>()
        var currentPrompt: String? = null
        var currentUserNonSystemMessageCount = 0
        val currentEvents = mutableListOf<TurnEvent>()

        fun flushTurn() {
            val prompt = currentPrompt?.trim().orEmpty()
            if (prompt.isBlank() || currentUserNonSystemMessageCount <= 0) {
                return
            }
            turns.add(
                Turn(
                    prompt = prompt,
                    userNonSystemMessageCount = currentUserNonSystemMessageCount,
                    events = currentEvents.toList()
                )
            )
            currentPrompt = null
            currentUserNonSystemMessageCount = 0
            currentEvents.clear()
        }

        history
            .filterNot { it is PromptMessage.System }
            .forEachIndexed { index, message ->
                val nonSystemMessageCount = index + 1
                when (message) {
                    is PromptMessage.User -> {
                        if (isSyntheticTimelineUserMessage(message)) {
                            if (
                                preserveSyntheticContinuation &&
                                currentPrompt != null &&
                                currentEvents.any {
                                    it is TurnEvent.ToolCall || it is TurnEvent.ToolResult
                                }
                            ) {
                                // Keep collecting events for the currently active visible turn.
                                // Synthetic todo prompts are injected internally mid-run.
                                return@forEachIndexed
                            }
                            flushTurn()
                            currentPrompt = null
                            currentUserNonSystemMessageCount = 0
                            return@forEachIndexed
                        }

                        flushTurn()
                        if (isHiddenUserMessage(message, projectInstructions)) {
                            currentPrompt = null
                            currentUserNonSystemMessageCount = 0
                            return@forEachIndexed
                        }
                        currentPrompt = message.content.trim()
                        currentUserNonSystemMessageCount = nonSystemMessageCount
                    }

                    is PromptMessage.Assistant -> {
                        if (currentPrompt != null) {
                            currentEvents.add(
                                TurnEvent.Assistant(
                                    content = message.content,
                                    nonSystemMessageCount = nonSystemMessageCount
                                )
                            )
                        }
                    }

                    is PromptMessage.Reasoning -> {
                        if (currentPrompt != null) {
                            currentEvents.add(
                                TurnEvent.Reasoning(
                                    content = message.content,
                                    nonSystemMessageCount = nonSystemMessageCount
                                )
                            )
                        }
                    }

                    is PromptMessage.Tool.Call -> {
                        if (currentPrompt != null && !isTodoWriteTool(message.tool)) {
                            currentEvents.add(
                                TurnEvent.ToolCall(
                                    id = message.id,
                                    tool = message.tool,
                                    content = message.content,
                                    nonSystemMessageCount = nonSystemMessageCount
                                )
                            )
                        }
                    }

                    is PromptMessage.Tool.Result -> {
                        if (currentPrompt != null && !isTodoWriteTool(message.tool)) {
                            currentEvents.add(
                                TurnEvent.ToolResult(
                                    id = message.id,
                                    tool = message.tool,
                                    content = message.content,
                                    nonSystemMessageCount = nonSystemMessageCount
                                )
                            )
                        }
                    }

                    else -> Unit
                }
            }

        flushTurn()
        return turns
    }

    fun isTodoWriteTool(toolName: String): Boolean {
        return toolName.equals("TodoWrite", ignoreCase = true)
    }

    fun isSyntheticTimelineUserMessage(message: PromptMessage.User): Boolean {
        val normalized = message.content.lowercase()
        return normalized.contains("haven't created a todo list yet")
    }

    private fun isHiddenUserMessage(
        message: PromptMessage.User,
        projectInstructions: String?
    ): Boolean {
        return isCacheableInstructionMessage(message) ||
                isProjectInstructionsMessage(message.content, projectInstructions)
    }

    private fun isCacheableInstructionMessage(message: PromptMessage.User): Boolean {
        val cacheable = message.metaInfo.metadata
            ?.get("cacheable")
            ?.jsonPrimitive
            ?: return false
        return cacheable.booleanOrNull ?: (cacheable.contentOrNull?.equals(
            "true",
            ignoreCase = true
        ) == true)
    }

    private fun isProjectInstructionsMessage(text: String, projectInstructions: String?): Boolean {
        if (projectInstructions.isNullOrBlank()) {
            return false
        }
        return normalize(text) == normalize(projectInstructions)
    }

    private fun normalize(value: String): String {
        return value.replace("\\s+".toRegex(), " ").trim()
    }
}
