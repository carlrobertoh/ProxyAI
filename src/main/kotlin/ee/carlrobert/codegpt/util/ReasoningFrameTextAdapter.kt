package ee.carlrobert.codegpt.util

import ai.koog.prompt.streaming.StreamFrame

internal class ReasoningFrameTextAdapter {

    private var thinkingOpen = false
    private val textDeltaIndexes = mutableSetOf<Int?>()
    private val reasoningDeltaIndexes = mutableSetOf<Int?>()

    fun consume(frame: StreamFrame): List<String> {
        return when (frame) {
            is StreamFrame.TextDelta -> emitTextDelta(frame)
            is StreamFrame.TextComplete -> emitTextComplete(frame)
            is StreamFrame.ReasoningDelta -> emitReasoning(frame.text, frame.summary, frame.index)
            is StreamFrame.ReasoningComplete -> emitReasoningComplete(frame)
            is StreamFrame.End -> {
                textDeltaIndexes.clear()
                reasoningDeltaIndexes.clear()
                closeThinking()
            }
            else -> emptyList()
        }
    }

    private fun emitTextDelta(frame: StreamFrame.TextDelta): List<String> {
        if (frame.text.isEmpty()) return emptyList()
        textDeltaIndexes.add(frame.index)
        return emitText(frame.text)
    }

    private fun emitTextComplete(frame: StreamFrame.TextComplete): List<String> {
        val hadDeltaForThisText = textDeltaIndexes.remove(frame.index)
        return if (hadDeltaForThisText) emptyList() else emitText(frame.text)
    }

    private fun emitText(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val parts = mutableListOf<String>()
        if (thinkingOpen) {
            parts.add(THINK_END)
            thinkingOpen = false
        }
        parts.add(text)
        return parts
    }

    private fun emitReasoning(text: String?, summary: String?, index: Int?): List<String> {
        val parts = mutableListOf<String>()
        var emitted = false

        if (!text.isNullOrBlank()) {
            if (!thinkingOpen) {
                parts.add(THINK_START)
                thinkingOpen = true
            }
            parts.add(text)
            emitted = true
        }

        if (!summary.isNullOrBlank()) {
            if (!thinkingOpen) {
                parts.add(THINK_START)
                thinkingOpen = true
            }
            parts.add(summary)
            emitted = true
        }

        if (emitted) {
            reasoningDeltaIndexes.add(index)
        }

        return parts
    }

    private fun emitReasoningComplete(frame: StreamFrame.ReasoningComplete): List<String> {
        val hadDeltaForThisReasoning = reasoningDeltaIndexes.remove(frame.index)
        if (hadDeltaForThisReasoning) {
            return closeThinking()
        }

        val reasoningText = frame.text.joinToString("\n").trim()
        val summaryText = frame.summary?.joinToString("\n")?.trim().orEmpty()

        val parts = mutableListOf<String>()
        if (reasoningText.isNotEmpty() || summaryText.isNotEmpty()) {
            if (!thinkingOpen) {
                parts.add(THINK_START)
                thinkingOpen = true
            }

            if (reasoningText.isNotEmpty()) {
                parts.add(reasoningText)
            }
            if (summaryText.isNotEmpty()) {
                parts.add(summaryText)
            }
        }

        parts.addAll(closeThinking())
        return parts
    }

    private fun closeThinking(): List<String> {
        return if (thinkingOpen) {
            thinkingOpen = false
            listOf(THINK_END)
        } else {
            emptyList()
        }
    }

    private companion object {
        private const val THINK_START = "<think>"
        private const val THINK_END = "</think>"
    }
}
