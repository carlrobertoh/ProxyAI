package ee.carlrobert.codegpt.codecompletions

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import org.apache.commons.text.similarity.LevenshteinDistance
import kotlin.math.min

class CodeCompletionFormatter(private val editor: Editor) {

    companion object {
        private val logger = thisLogger()

        private val OPENING_BRACKETS = listOf('(', '[', '{')
        private val CLOSING_BRACKETS = listOf(')', ']', '}')
        private val QUOTES = listOf('\'', '"', '`')
        private val BRACKET_PAIRS = mapOf(
            '(' to ')',
            '[' to ']',
            '{' to '}'
        )
    }

    private val languageId = editor.virtualFile?.fileType?.name
    private val cursorPosition = runReadAction { editor.caretModel.offset }
    private val document = editor.document
    private val lineNumber = document.getLineNumber(cursorPosition)
    private val lineStartOffset = document.getLineStartOffset(lineNumber)
    private val lineEndOffset = document.getLineEndOffset(lineNumber)
    private val textAfterCursor = document.getText(TextRange(cursorPosition, lineEndOffset))
    private val charAfterCursor = if (textAfterCursor.isNotEmpty()) textAfterCursor[0] else ' '
    private val charBeforeCursor = if (cursorPosition > lineStartOffset)
        document.getText(TextRange(cursorPosition - 1, cursorPosition))[0] else ' '
    private var completion = ""
    private var normalizedCompletion = ""
    private var originalCompletion = ""
    private var isDebugEnabled = false

    fun withDebug(): CodeCompletionFormatter {
        isDebugEnabled = true
        return this
    }

    fun format(completion: String): String {
        this.completion = ""
        this.normalizedCompletion = completion.trim()
        this.originalCompletion = completion

        return matchCompletionBrackets()
            .removeSuffix()
            .removeDuplicateQuotes()
            .removeMiddleQuotes()
            .ignoreBlankLines()
            .removeOverlapText()
            .trimStart()
            .preventDuplicates()
            .getCompletion()
    }

    private fun isMatchingPair(open: Char?, close: Char?): Boolean {
        return BRACKET_PAIRS[open] == close
    }

    private fun removeSuffix(): CodeCompletionFormatter {
        completion = completion.removeSuffix(textAfterCursor)
        return this
    }

    private fun matchCompletionBrackets(): CodeCompletionFormatter {
        var accumulatedCompletion = ""
        val openBrackets = mutableListOf<Char>()
        var inString = false
        var stringChar = ' '

        for (char in originalCompletion) {
            if (char in QUOTES) {
                if (!inString) {
                    inString = true
                    stringChar = char
                } else if (char == stringChar) {
                    inString = false
                    stringChar = ' '
                }
            }

            if (!inString) {
                if (char in OPENING_BRACKETS) {
                    openBrackets.add(char)
                } else if (char in CLOSING_BRACKETS) {
                    val lastOpen = openBrackets.lastOrNull()
                    if (lastOpen != null && isMatchingPair(lastOpen, char)) {
                        openBrackets.removeAt(openBrackets.size - 1)
                    } else {
                        break
                    }
                }
            }

            accumulatedCompletion += char
        }

        completion = accumulatedCompletion.trimEnd().ifEmpty { originalCompletion.trimEnd() }

        if (isDebugEnabled) {
            logger.info("After matchCompletionBrackets: $completion")
        }

        return this
    }

    private fun ignoreBlankLines(): CodeCompletionFormatter {
        if (completion.trimStart().isEmpty() && originalCompletion != "\n") {
            completion = completion.trim()
        }

        if (isDebugEnabled) {
            logger.info("After ignoreBlankLines: $completion")
        }

        return this
    }

    private fun removeOverlapText(): CodeCompletionFormatter {
        val after = textAfterCursor.trim()
        if (after.isEmpty() || completion.isEmpty()) return this

        val maxLength = min(completion.length, after.length)
        var overlapLength = 0

        for (length in maxLength downTo 1) {
            val endOfCompletion = completion.takeLast(length)
            val startOfAfter = after.take(length)
            if (endOfCompletion == startOfAfter) {
                overlapLength = length
                break
            }
        }

        if (overlapLength > 0) {
            completion = completion.dropLast(overlapLength)
        }

        if (isDebugEnabled) {
            logger.info("After removeDuplicateText: $completion")
        }

        return this
    }

    private fun isCursorAtMiddleOfWord(): Boolean {
        val isAfterWord = charAfterCursor.toString().matches(Regex("\\w"))
        val isBeforeWord = charBeforeCursor.toString().matches(Regex("\\w"))

        if (!isAfterWord || !isBeforeWord) return false

        if (languageId?.lowercase() in listOf("javascript", "typescript", "php")) {
            if (charBeforeCursor == '$' || charAfterCursor == '$') {
                return true
            }
        }

        if (charBeforeCursor == '_' || charAfterCursor == '_') {
            return true
        }

        return true
    }

    private fun removeMiddleQuotes(): CodeCompletionFormatter {
        if (isCursorAtMiddleOfWord()) {
            if (completion.isNotEmpty() && completion[0] in QUOTES) {
                completion = completion.substring(1)
            }

            if (completion.isNotEmpty() && completion.last() in QUOTES) {
                completion = completion.dropLast(1)
            }
        }

        if (isDebugEnabled) {
            logger.info("After removeUnnecessaryMiddleQuotes: $completion")
        }

        return this
    }

    private fun isSimilarCode(s1: String, s2: String): Double {
        val distance = LevenshteinDistance.getDefaultInstance().apply(s1, s2)
        val maxLength = maxOf(s1.length, s2.length)
        return 1.0 - (distance.toDouble() / maxLength)
    }

    private fun removeDuplicateQuotes(): CodeCompletionFormatter {
        val trimmedCharAfterCursor = charAfterCursor.toString().trim()
        val normalizedCompletion = completion.trim()
        val lastCharOfCompletion =
            if (normalizedCompletion.isNotEmpty()) normalizedCompletion.last() else ' '

        if (trimmedCharAfterCursor.isNotEmpty() &&
            (normalizedCompletion.endsWith("',") ||
                    normalizedCompletion.endsWith("\",") ||
                    normalizedCompletion.endsWith("`,") ||
                    (normalizedCompletion.endsWith(",") && trimmedCharAfterCursor[0] in QUOTES))
        ) {
            completion = completion.dropLast(2)
        } else if ((normalizedCompletion.endsWith("'") ||
                    normalizedCompletion.endsWith("\"") ||
                    normalizedCompletion.endsWith("`")) &&
            trimmedCharAfterCursor.isNotEmpty() && trimmedCharAfterCursor[0] in QUOTES
        ) {
            completion = completion.dropLast(1)
        } else if (lastCharOfCompletion in QUOTES &&
            trimmedCharAfterCursor.isNotEmpty() &&
            trimmedCharAfterCursor[0] == lastCharOfCompletion
        ) {
            completion = completion.dropLast(1)
        }

        if (isDebugEnabled) {
            logger.info("After removeDuplicateQuotes: $completion")
        }

        return this
    }

    private fun preventDuplicates(): CodeCompletionFormatter {
        val lineCount = document.lineCount
        val originalNormalized = originalCompletion.trim()

        for (i in 1..3) {
            val nextLineIndex = lineNumber + i
            if (nextLineIndex >= lineCount) break

            val nextLineStartOffset = document.getLineStartOffset(nextLineIndex)
            val nextLineEndOffset = document.getLineEndOffset(nextLineIndex)
            val nextLine = document.getText(TextRange(nextLineStartOffset, nextLineEndOffset))
            val nextLineNormalized = nextLine.trim()

            if (nextLineNormalized == originalNormalized) {
                completion = ""
                break
            }

            if (isSimilarCode(nextLineNormalized, originalNormalized) > 0.8) {
                completion = ""
                break
            }
        }

        if (isDebugEnabled) {
            logger.info("After preventDuplicateLine: $completion")
        }

        return this
    }

    private fun getCompletion(): String {
        if (completion.trim().isEmpty()) {
            completion = ""
        }
        return completion
    }

    private fun trimStart(): CodeCompletionFormatter {
        val firstNonSpaceIndex = completion.indexOfFirst { !it.isWhitespace() }
        if (firstNonSpaceIndex > 0 && (cursorPosition - lineStartOffset) <= firstNonSpaceIndex) {
            completion = completion.trimStart()
        }

        if (isDebugEnabled) {
            logger.info("After trimStart: $completion")
        }

        return this
    }
}