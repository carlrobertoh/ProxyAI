package ee.carlrobert.codegpt.agent.history

import ai.koog.prompt.message.Message

internal object AgentMessageHistorySanitizer {

    fun sanitizeForNewUserTurn(history: List<Message>): List<Message> {
        val sanitized = mutableListOf<Message>()
        val pendingToolCallIds = mutableListOf<String>()
        var firstPendingToolCallIndex: Int? = null
        var anonymousToolCallIndex = 0

        fun discardPendingToolCalls() {
            val truncateFrom = firstPendingToolCallIndex ?: return
            while (sanitized.size > truncateFrom) {
                sanitized.removeAt(sanitized.lastIndex)
            }
            pendingToolCallIds.clear()
            firstPendingToolCallIndex = null
        }

        history.forEach { message ->
            when (message) {
                is Message.Tool.Call -> {
                    if (firstPendingToolCallIndex == null) {
                        firstPendingToolCallIndex = sanitized.size
                    }
                    pendingToolCallIds.add(message.id?.takeIf { it.isNotBlank() }
                        ?: "__anonymous_tool_call_${++anonymousToolCallIndex}")
                    sanitized.add(message)
                }

                is Message.Tool.Result -> {
                    if (pendingToolCallIds.isEmpty()) {
                        return@forEach
                    }

                    val matched = if (message.id.isNullOrBlank()) {
                        pendingToolCallIds.removeAt(0)
                        true
                    } else {
                        pendingToolCallIds.remove(message.id)
                    }
                    if (!matched) {
                        return@forEach
                    }

                    sanitized.add(message)
                    if (pendingToolCallIds.isEmpty()) {
                        firstPendingToolCallIndex = null
                    }
                }

                else -> {
                    if (pendingToolCallIds.isNotEmpty()) {
                        discardPendingToolCalls()
                    }
                    sanitized.add(message)
                }
            }
        }

        if (pendingToolCallIds.isNotEmpty()) {
            discardPendingToolCalls()
        }

        return sanitized
    }

    fun isSafeForNewUserTurn(history: List<Message>): Boolean {
        return sanitizeForNewUserTurn(history).size == history.size
    }
}
