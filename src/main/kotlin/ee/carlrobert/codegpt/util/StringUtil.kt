package ee.carlrobert.codegpt.util

object StringUtil {

    private val thinkBlockRegex = Regex("(?s)<think>.*?</think>\\s*")

    fun String.stripThinkingBlocks(): String {
        if (this.isBlank()) return ""
        return thinkBlockRegex.replace(this, "").trim()
    }

    fun adjustWhitespace(
        completionLine: String,
        editorLine: String
    ): String {
        val editorWhitespaces = editorLine.leadingWhitespaces()

        if (completionLine.isNotEmpty() && editorWhitespaces.isNotEmpty()) {
            if (completionLine.startsWith(editorWhitespaces)) {
                return completionLine.substring(editorWhitespaces.length)
            }
            if (editorLine.isBlank()) {
                val completionWhitespaces = completionLine.leadingWhitespaces()
                return completionLine.substring(completionWhitespaces.length)
            }
        }

        return completionLine
    }

    fun getDiceCoefficient(s1: String, s2: String): Double {
        fun bigrams(str: String): Set<String> =
            if (str.length < 2) emptySet()
            else str.windowed(2).toSet()

        val bigrams1 = bigrams(s1)
        val bigrams2 = bigrams(s2)
        val intersection = bigrams1.intersect(bigrams2).size
        return (2.0 * intersection) / (bigrams1.size + bigrams2.size)
    }

    private fun String.leadingWhitespaces(): String = takeWhile { it.isWhitespace() }
}
