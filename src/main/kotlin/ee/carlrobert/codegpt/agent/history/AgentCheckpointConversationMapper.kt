package ee.carlrobert.codegpt.agent.history

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.llm.client.openai.completion.response.ToolCall
import ee.carlrobert.llm.client.openai.completion.response.ToolFunctionResponse
import ai.koog.prompt.message.Message as PromptMessage

object AgentCheckpointConversationMapper {

    fun toConversation(
        checkpoint: AgentCheckpointData,
        projectInstructions: String?
    ): Conversation {
        val conversation = Conversation()
        val history = checkpoint.messageHistory.filterNot { it is PromptMessage.System }

        var currentPrompt: String? = null
        val response = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        val toolResults = LinkedHashMap<String, String>()
        var syntheticToolIdIndex = 0

        fun flushTurn() {
            val prompt = currentPrompt?.trim().orEmpty()
            if (prompt.isBlank()) {
                return
            }

            val uiMessage = Message(prompt)
            uiMessage.response = response.toString().trim()
            if (toolCalls.isNotEmpty()) {
                uiMessage.toolCalls = ArrayList(toolCalls)
            }
            if (toolResults.isNotEmpty()) {
                uiMessage.toolCallResults = LinkedHashMap(toolResults)
            }
            conversation.addMessage(uiMessage)
            currentPrompt = null
            response.setLength(0)
            toolCalls.clear()
            toolResults.clear()
        }

        history.forEach { msg ->
            when (msg) {
                is PromptMessage.User -> {
                    val text = msg.content.trim()
                    if (shouldHideInAgentToolWindow(msg, projectInstructions)) {
                        return@forEach
                    }
                    flushTurn()
                    currentPrompt = text
                }

                is PromptMessage.Assistant -> appendAssistant(response, msg.content)
                is PromptMessage.Reasoning -> appendAssistant(response, msg.content)
                is PromptMessage.Tool.Call -> {
                    if (currentPrompt != null) {
                        val callId =
                            msg.id?.takeIf { it.isNotBlank() }
                                ?: "tool-call-${++syntheticToolIdIndex}"
                        toolCalls.add(
                            ToolCall(
                                null,
                                callId,
                                "function",
                                ToolFunctionResponse(msg.tool, msg.content.trim())
                            )
                        )
                    }
                }

                is PromptMessage.Tool.Result -> {
                    if (currentPrompt != null) {
                        val callId = msg.id?.takeIf { it.isNotBlank() }
                            ?: toolCalls.lastOrNull()?.id
                            ?: "tool-call-${++syntheticToolIdIndex}"
                        val prior = toolResults[callId]
                        val merged = if (prior.isNullOrBlank()) msg.content.trim() else {
                            "$prior\n\n${msg.content.trim()}"
                        }
                        toolResults[callId] = merged
                    }
                }

                else -> Unit
            }
        }

        flushTurn()
        return conversation
    }

    private fun appendAssistant(sb: StringBuilder, content: String) {
        val text = content.trim()
        if (text.isBlank()) {
            return
        }
        if (sb.isNotEmpty()) {
            sb.append("\n\n")
        }
        sb.append(text)
    }
}
