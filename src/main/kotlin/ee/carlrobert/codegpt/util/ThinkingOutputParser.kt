package ee.carlrobert.codegpt.util

class ThinkingOutputParser {

    companion object {
        private const val OPEN_TAG = "<think>"
        private const val CLOSE_TAG = "</think>\n\n"
    }

    var thoughtProcess: String = ""
        private set
    var isThinking: Boolean = false
        private set
    var isFinished: Boolean = false
        private set

    private val buffer = StringBuilder()

    fun processChunk(chunk: String): String {
        if (isFinished) {
            return chunk
        }

        if (buffer.isEmpty() && chunk.isNotEmpty() && !OPEN_TAG.contains(chunk.take(OPEN_TAG.length))) {
            isFinished = true
            return chunk
        }

        buffer.append(chunk)
        val current = buffer.toString()

        val indexOpen = current.indexOf(OPEN_TAG)
        if (indexOpen == -1) {
            return ""
        }

        isThinking = true
        val startContent = indexOpen + OPEN_TAG.length

        val indexClose = current.indexOf(CLOSE_TAG, startContent)
        if (indexClose != -1) {
            thoughtProcess = current.substring(startContent, indexClose).trim()
            isFinished = true
            isThinking = false

            val responseStart = indexClose + CLOSE_TAG.length
            return current.substring(responseStart)
        } else {
            thoughtProcess = current.substring(startContent)
            return ""
        }
    }
}