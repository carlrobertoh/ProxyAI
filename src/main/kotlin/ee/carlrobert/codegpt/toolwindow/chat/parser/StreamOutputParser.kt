package ee.carlrobert.codegpt.toolwindow.chat.parser

sealed class StreamParseResponse(
    val type: StreamResponseType,
    val content: String,
    val language: String? = null,
    val filePath: String? = null
) {
    enum class StreamResponseType {
        TEXT, THINKING, CODE_HEADER, CODE_CONTENT, CODE_END
    }

    data class Text(val textContent: String) :
        StreamParseResponse(StreamResponseType.TEXT, textContent)

    data class Thinking(val thoughtProcess: String) :
        StreamParseResponse(StreamResponseType.THINKING, thoughtProcess)

    data class CodeHeader(val codeLanguage: String, val codeFilePath: String?) :
        StreamParseResponse(StreamResponseType.CODE_HEADER, "", codeLanguage, codeFilePath)

    data class CodeContent(
        val codeContent: String,
        val codeLanguage: String,
        val codeFilePath: String?
    ) :
        StreamParseResponse(
            StreamResponseType.CODE_CONTENT,
            codeContent,
            codeLanguage,
            codeFilePath
        )

    data class CodeEnd(val codeLanguage: String, val codeFilePath: String?) :
        StreamParseResponse(StreamResponseType.CODE_END, "", codeLanguage, codeFilePath)
}

class StreamOutputParser {
    companion object {
        private val CODE_BLOCK_PATTERN = Regex("```([a-zA-Z0-9_+-]*)(?::([^\\n]*))?\\n")
        private const val THINK_START_TAG = "<think>"
        private const val THINK_END_TAG = "</think>"
    }

    private val messageBuilder = StringBuilder()
    private var isProcessingCode = false
    private var currentLanguage: String? = null
    private var currentFilePath: String? = null

    private fun handleProcessText(matcher: MatchResult): List<StreamParseResponse> {
        val responses = mutableListOf<StreamParseResponse>()
        isProcessingCode = true

        val startingIndex = matcher.range.first
        val prevMessage = messageBuilder.substring(0, startingIndex)

        currentLanguage = matcher.groupValues[1].takeIf { it.isNotEmpty() } ?: ""
        currentFilePath = matcher.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }

        messageBuilder.delete(0, startingIndex + matcher.value.length)

        if (prevMessage.isNotEmpty()) {
            responses.add(StreamParseResponse.Text(prevMessage))
        }

        responses.add(
            StreamParseResponse.CodeHeader(
                currentLanguage ?: "",
                currentFilePath
            )
        )

        if (messageBuilder.isNotEmpty()) {
            responses.add(
                StreamParseResponse.CodeContent(
                    messageBuilder.toString(),
                    currentLanguage ?: "",
                    currentFilePath
                )
            )
        }

        return responses
    }

    private fun handleThinking(): List<StreamParseResponse> {
        val startIndex = messageBuilder.indexOf(THINK_START_TAG)

        if (messageBuilder.contains("</")) {
            val endIndex =
                messageBuilder.indexOf(THINK_END_TAG, startIndex + THINK_START_TAG.length)
            if (endIndex != -1) {
                messageBuilder.delete(startIndex, endIndex + THINK_END_TAG.length)
            }
        }

        val partialEndIndex = messageBuilder.indexOf("</", startIndex + THINK_START_TAG.length)

        val contentEndIndex = if (partialEndIndex != -1) {
            partialEndIndex
        } else {
            messageBuilder.length
        }

        val contentStartIndex = startIndex + THINK_START_TAG.length
        if (contentStartIndex > contentEndIndex) {
            return listOf(StreamParseResponse.Thinking(""))
        }

        val thoughtContent = messageBuilder.substring(contentStartIndex, contentEndIndex)
        return listOf(StreamParseResponse.Thinking(thoughtContent))
    }

    private fun handleProcessCode(endingIndex: Int): List<StreamParseResponse> {
        val responses = mutableListOf<StreamParseResponse>()
        isProcessingCode = false

        val codeContent = messageBuilder.substring(0, endingIndex)

        var deleteEndIndex = endingIndex + 3
        if (deleteEndIndex < messageBuilder.length && messageBuilder[deleteEndIndex] == '\n') {
            deleteEndIndex++
        }
        messageBuilder.delete(0, deleteEndIndex)

        if (codeContent.isNotEmpty()) {
            responses.add(
                StreamParseResponse.CodeContent(
                    codeContent,
                    currentLanguage ?: "",
                    currentFilePath
                )
            )
        }

        responses.add(
            StreamParseResponse.CodeEnd(
                currentLanguage ?: "",
                currentFilePath
            )
        )

        if (messageBuilder.isNotEmpty()) {
            responses.add(StreamParseResponse.Text(messageBuilder.toString()))
        }

        return responses
    }

    fun parse(message: String): List<StreamParseResponse> {
        val sanitizedMessage = message.replace("\r", "")
        messageBuilder.append(sanitizedMessage)

        if (messageBuilder.length < THINK_START_TAG.length) {
            return emptyList();
        }

        val isThinking =
            messageBuilder.startsWith(THINK_START_TAG) || THINK_START_TAG.startsWith(messageBuilder)
        if (isThinking) {
            return handleThinking()
        }

        if (isProcessingCode) {
            val endingIndex = messageBuilder.indexOf("```")
            if (endingIndex >= 0) {
                return handleProcessCode(endingIndex)
            }
        } else {
            val matcher = CODE_BLOCK_PATTERN.find(messageBuilder.toString())
            if (matcher != null) {
                return handleProcessText(matcher)
            }
        }

        return if (isProcessingCode) {
            listOf(
                StreamParseResponse.CodeContent(
                    messageBuilder.toString(),
                    currentLanguage ?: "",
                    currentFilePath
                )
            )
        } else {
            listOf(StreamParseResponse.Text(messageBuilder.toString()))
        }
    }

    fun clear() {
        messageBuilder.setLength(0)
        isProcessingCode = false
        currentLanguage = null
        currentFilePath = null
    }
}
