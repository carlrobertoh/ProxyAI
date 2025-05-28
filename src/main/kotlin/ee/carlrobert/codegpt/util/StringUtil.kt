package ee.carlrobert.codegpt.util

import ai.grazie.nlp.utils.takeWhitespaces

object StringUtil {

    fun adjustWhitespace(
        completionLine: String,
        editorLine: String
    ): String {
        val editorWhitespaces = editorLine.takeWhitespaces()

        if (completionLine.isNotEmpty() && editorWhitespaces.isNotEmpty()) {
            if (completionLine.startsWith(editorWhitespaces)) {
                return completionLine.substring(editorWhitespaces.length)
            }
            if (editorLine.isBlank()) {
                val completionWhitespaces = completionLine.takeWhitespaces()
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

    fun String.extractUntilNewline(): String {
        val index = this.indexOf('\n')
        if (index == -1) {
            return this
        }
        return this.substring(0, index + 1)
    }
}
