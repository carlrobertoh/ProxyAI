package ee.carlrobert.codegpt.toolwindow.chat.parser

import java.util.regex.Pattern

class CompleteOutputParser {

    companion object {
        private val CODE_BLOCK_PATTERN: Pattern =
            Pattern.compile("```([a-zA-Z0-9_+-]*)(?::([^\\n]*))?\\n(.*?)```", Pattern.DOTALL)
        private const val THINK_OPEN_TAG = "<think>"
        private const val THINK_CLOSE_TAG = "</think>\n\n"
    }

    var extractedThought: String? = null
        private set

    /**
     * Parses a complete text output, extracts an optional initial thought block,
     * and identifies code blocks and regular text in the remaining content.
     *
     * @param completeOutput The full text output to parse
     * @return A list of parsed response segments (excluding the thought)
     */
    fun parse(completeOutput: String): List<StreamParseResponse> {
        extractedThought = null
        var contentToParse = completeOutput.replace("\r", "")

        if (contentToParse.startsWith(THINK_OPEN_TAG)) {
            val closeTagIndex = contentToParse.indexOf(THINK_CLOSE_TAG)
            if (closeTagIndex != -1) {
                val startContent = THINK_OPEN_TAG.length
                extractedThought = contentToParse.substring(startContent, closeTagIndex).trim()
                contentToParse = contentToParse.substring(closeTagIndex + THINK_CLOSE_TAG.length)
            }
        }

        return buildList {
            val matcher = CODE_BLOCK_PATTERN.matcher(contentToParse)
            var lastEnd = 0

            while (matcher.find()) {
                if (matcher.start() > lastEnd) {
                    val textBefore = contentToParse.substring(lastEnd, matcher.start())
                    if (textBefore.isNotEmpty()) {
                        add(StreamParseResponse.Text(textBefore))
                    }
                }

                val language = matcher.group(1) ?: ""
                val filePath: String? = matcher.group(2)
                val codeContent = matcher.group(3) ?: ""

                add(StreamParseResponse.CodeHeader(language, filePath))
                add(StreamParseResponse.CodeContent(codeContent, language, filePath))
                add(StreamParseResponse.CodeEnd(language, filePath))

                lastEnd = matcher.end()
            }

            if (lastEnd < contentToParse.length) {
                val remainingText = contentToParse.substring(lastEnd)
                if (remainingText.isNotEmpty()) {
                    add(StreamParseResponse.Text(remainingText))
                }
            }
        }
    }
}