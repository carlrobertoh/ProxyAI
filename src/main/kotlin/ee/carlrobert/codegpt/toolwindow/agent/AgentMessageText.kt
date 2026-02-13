package ee.carlrobert.codegpt.toolwindow.agent

internal object AgentMessageText {
    fun abbreviate(text: String, maxLength: Int): String {
        val normalized = text.replace("\\s+".toRegex(), " ").trim()
        if (normalized.length <= maxLength) return normalized
        return normalized.take(maxLength - 1).trimEnd() + "…"
    }
}
