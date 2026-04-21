package ee.carlrobert.codegpt.agent.history

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ee.carlrobert.codegpt.completions.ChatToolCall
import ee.carlrobert.codegpt.completions.ChatToolFunction
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.codegpt.util.StringUtil.stripThinkingBlocks

object AgentCheckpointConversationMapper {

    fun toConversation(
        checkpoint: AgentCheckpointData,
        projectInstructions: String?
    ): Conversation {
        return toConversation(checkpoint.messageHistory, projectInstructions)
    }

    fun toConversation(
        history: List<ai.koog.prompt.message.Message>,
        projectInstructions: String?
    ): Conversation {
        val conversation = Conversation()
        val turns = AgentCheckpointTurnSequencer.toVisibleTurns(
            history = history,
            projectInstructions = projectInstructions,
            preserveSyntheticContinuation = true
        )

        turns.forEach { turn ->
            val response = StringBuilder()
            val toolCalls = mutableListOf<ChatToolCall>()
            val toolResults = LinkedHashMap<String, String>()
            var syntheticToolIdIndex = 0

            turn.events.forEach { event ->
                when (event) {
                    is AgentCheckpointTurnSequencer.TurnEvent.Assistant -> {
                        appendAssistant(response, event.content)
                    }

                    is AgentCheckpointTurnSequencer.TurnEvent.Reasoning -> {
                        appendAssistant(response, event.content)
                    }

                    is AgentCheckpointTurnSequencer.TurnEvent.ToolCall -> {
                        val callId = event.id?.takeIf { it.isNotBlank() }
                            ?: "tool-call-${++syntheticToolIdIndex}"
                        toolCalls.add(
                            ChatToolCall(
                                null,
                                callId,
                                "function",
                                ChatToolFunction(event.tool, event.content.trim())
                            )
                        )
                    }

                    is AgentCheckpointTurnSequencer.TurnEvent.ToolResult -> {
                        val callId = event.id?.takeIf { it.isNotBlank() }
                            ?: toolCalls.lastOrNull()?.id
                            ?: "tool-call-${++syntheticToolIdIndex}"
                        val prior = toolResults[callId]
                        val merged = if (prior.isNullOrBlank()) event.content.trim() else {
                            "$prior\n\n${event.content.trim()}"
                        }
                        toolResults[callId] = merged
                    }
                }
            }

            val uiMessage = Message(turn.prompt)
            uiMessage.response = response.toString().trim()
            if (toolCalls.isNotEmpty()) {
                uiMessage.toolCalls = ArrayList(toolCalls)
            }
            if (toolResults.isNotEmpty()) {
                uiMessage.toolCallResults = LinkedHashMap(toolResults)
            }
            conversation.addMessage(uiMessage)
        }

        return conversation
    }

    private fun appendAssistant(sb: StringBuilder, content: String) {
        val text = content.stripThinkingBlocks()
        if (text.isBlank()) {
            return
        }
        if (sb.isNotEmpty()) {
            sb.append("\n\n")
        }
        sb.append(text)
    }
}
