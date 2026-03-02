package ee.carlrobert.codegpt.completions

import ai.koog.prompt.message.Message

internal object CompletionTextExtractor {
    fun extract(responses: List<Message.Response>): String {
        val reasoningText = responses
            .filterIsInstance<Message.Reasoning>()
            .map { it.content.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

        val assistantText = responses
            .filterIsInstance<Message.Assistant>()
            .joinToString("\n") { it.content.trim() }
            .trim()

        if (reasoningText.isNotEmpty() && assistantText.isNotEmpty()) {
            return "<think>$reasoningText</think>\n$assistantText"
        }

        if (assistantText.isNotEmpty()) {
            return assistantText
        }

        if (reasoningText.isNotEmpty()) {
            return "<think>$reasoningText</think>"
        }

        return responses.joinToString("\n") { it.content.trim() }.trim()
    }
}
