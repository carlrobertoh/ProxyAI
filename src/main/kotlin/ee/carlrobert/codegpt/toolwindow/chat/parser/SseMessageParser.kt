package ee.carlrobert.codegpt.toolwindow.chat.parser

class SseMessageParser : MessageParser {

    private companion object {
        const val CODE_FENCE = "```"
        const val THINK_START = "<think>"
        const val THINK_END = "</think>"
        const val SEARCH_MARKER = "<<<<<<< SEARCH"
        const val SEPARATOR_MARKER = "======="
        const val REPLACE_MARKER = ">>>>>>> REPLACE"
        const val NEWLINE = "\n"
        const val HEADER_DELIMITER = ":"
        const val HEADER_PARTS_LIMIT = 2
    }

    private var parserState: ParserState = ParserState.Outside
    private val buffer = StringBuilder()

    fun clear() {
        parserState = ParserState.Outside
        buffer.clear()
    }

    override fun parse(input: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var position = 0

        while (position < input.length) {
            val endPosition = minOf(position + 16, input.length)
            val chunk = input.substring(position, endPosition)
            buffer.append(chunk)

            while (processNextSegment(segments)) {
            }

            position = endPosition
        }
        segments.addAll(getPendingSegments())

        return segments
    }

    private fun processNextSegment(segments: MutableList<Segment>): Boolean {
        return when (val state = parserState) {
            is ParserState.Outside -> processOutsideState(segments)
            is ParserState.CodeHeaderWaiting -> processCodeHeaderState(segments, state)
            is ParserState.InCode -> processInCodeState(segments, state)
            is ParserState.InSearch -> processInSearchState(segments, state)
            is ParserState.InReplace -> processInReplaceState(segments, state)
            is ParserState.InThinking -> processInThinkingState(segments, state)
        }
    }

    private fun processOutsideState(segments: MutableList<Segment>): Boolean {
        val fenceIdx = buffer.indexOf(CODE_FENCE)
        val thinkStartIdx = buffer.indexOf(THINK_START)

        return when {
            shouldProcessCodeFence(fenceIdx, thinkStartIdx) -> {
                extractTextBeforeIndex(fenceIdx)?.let { segments.add(it) }
                consumeFromBuffer(fenceIdx + CODE_FENCE.length)
                parserState = ParserState.CodeHeaderWaiting()
                true
            }

            thinkStartIdx != -1 -> {
                extractTextBeforeIndex(thinkStartIdx)?.let { segments.add(it) }
                consumeFromBuffer(thinkStartIdx + THINK_START.length)
                parserState = ParserState.InThinking()
                true
            }

            else -> false
        }
    }

    private fun processCodeHeaderState(
        segments: MutableList<Segment>,
        state: ParserState.CodeHeaderWaiting
    ): Boolean {
        val nlIdx = buffer.indexOf(NEWLINE)
        if (nlIdx < 0) return false

        val headerLine = buffer.substring(0, nlIdx).trim()
        consumeFromBuffer(nlIdx + 1)

        val updatedHeader = state.content + headerLine
        val header = parseCodeHeader(updatedHeader)

        return if (header != null) {
            segments.add(header)
            parserState = ParserState.InCode(header)
            true
        } else {
            segments.add(CodeHeaderWaiting(updatedHeader))
            parserState = ParserState.CodeHeaderWaiting(updatedHeader)
            false
        }
    }

    private fun processInCodeState(
        segments: MutableList<Segment>,
        state: ParserState.InCode
    ): Boolean {
        val nlIdx = buffer.indexOf(NEWLINE)
        if (nlIdx < 0) return false

        val line = buffer.substring(0, nlIdx)
        consumeFromBuffer(nlIdx + 1)

        return when {
            line.trim() == CODE_FENCE -> {
                if (state.content.isNotEmpty()) {
                    segments.add(Code(state.content, state.header.language, state.header.filePath))
                }
                segments.add(CodeEnd(""))
                parserState = ParserState.Outside
                true
            }

            line.trimStart().startsWith(SEARCH_MARKER) -> {
                // Emit accumulated code content before transitioning
                if (state.content.isNotEmpty()) {
                    segments.add(Code(state.content, state.header.language, state.header.filePath))
                }
                segments.add(SearchWaiting("", state.header.language, state.header.filePath))
                parserState = ParserState.InSearch(state.header, "")
                true
            }

            else -> {
                val newContent =
                    if (state.content.isEmpty()) line else state.content + NEWLINE + line
                parserState = ParserState.InCode(state.header, newContent)
                true
            }
        }
    }

    private fun processInSearchState(
        segments: MutableList<Segment>,
        state: ParserState.InSearch
    ): Boolean {
        val nlIdx = buffer.indexOf(NEWLINE)
        if (nlIdx < 0) return false

        val line = buffer.substring(0, nlIdx)
        consumeFromBuffer(nlIdx + 1)

        return if (line.trim() == SEPARATOR_MARKER) {
            segments.add(
                ReplaceWaiting(
                    state.searchContent,
                    "",
                    state.header.language,
                    state.header.filePath
                )
            )
            parserState = ParserState.InReplace(state.header, state.searchContent, "")
            true
        } else {
            val newSearch =
                if (state.searchContent.isEmpty()) line else state.searchContent + NEWLINE + line
            segments.add(SearchWaiting(newSearch, state.header.language, state.header.filePath))
            parserState = ParserState.InSearch(state.header, newSearch)
            false
        }
    }

    private fun processInReplaceState(
        segments: MutableList<Segment>,
        state: ParserState.InReplace
    ): Boolean {
        val nlIdx = buffer.indexOf(NEWLINE)
        if (nlIdx < 0) return false

        val line = buffer.substring(0, nlIdx)
        consumeFromBuffer(nlIdx + 1)

        return when {
            line.trim().startsWith(REPLACE_MARKER) -> {
                segments.add(
                    SearchReplace(
                        search = state.searchContent,
                        replace = state.replaceContent,
                        language = state.header.language,
                        filePath = state.header.filePath
                    )
                )
                parserState = ParserState.InCode(state.header)
                true
            }

            line.trim() == CODE_FENCE -> {
                // Invalid search/replace block - missing REPLACE marker
                // Mark done
                segments.add(CodeEnd(""))
                parserState = ParserState.Outside
                true
            }

            else -> {
                val newReplace =
                    if (state.replaceContent.isEmpty()) line else state.replaceContent + NEWLINE + line
                segments.add(
                    ReplaceWaiting(
                        state.searchContent,
                        newReplace,
                        state.header.language,
                        state.header.filePath
                    )
                )
                parserState = ParserState.InReplace(state.header, state.searchContent, newReplace)

                val nlIdx = buffer.indexOf(NEWLINE)
                if (nlIdx < 0) return false

                val line = buffer.substring(0, nlIdx)
                if (line.trim().startsWith(REPLACE_MARKER)) {
                    consumeFromBuffer(nlIdx + 1)
                    segments.add(
                        SearchReplace(
                            state.searchContent,
                            newReplace,
                            state.header.language,
                            state.header.filePath
                        )
                    )
                    return true
                }

                false
            }
        }
    }

    private fun processInThinkingState(
        segments: MutableList<Segment>,
        state: ParserState.InThinking
    ): Boolean {
        val thinkEndIdx = buffer.indexOf(THINK_END)

        return if (thinkEndIdx >= 0) {
            val thinkingContent = state.content + buffer.substring(0, thinkEndIdx)
            segments.add(Thinking(thinkingContent))
            consumeFromBuffer(thinkEndIdx + THINK_END.length)
            parserState = ParserState.Outside
            true
        } else {
            if (buffer.isNotEmpty()) {
                val newContent = state.content + buffer.toString()
                segments.add(Thinking(newContent))
                buffer.clear()
                parserState = ParserState.InThinking(newContent)
            }
            false
        }
    }

    private fun getPendingSegments(): List<Segment> {
        return when (val state = parserState) {
            is ParserState.Outside -> {
                if (buffer.isNotBlank()) listOf(Text(buffer.toString()))
                else emptyList()
            }

            is ParserState.CodeHeaderWaiting -> {
                if (state.content.isNotBlank()) listOf(CodeHeaderWaiting(state.content))
                else emptyList()
            }

            is ParserState.InCode -> {
                val segments = mutableListOf<Segment>()

                if (buffer.toString().trim() == CODE_FENCE) {
                    if (state.content.isNotBlank()) {
                        segments.add(
                            Code(state.content, state.header.language, state.header.filePath)
                        )
                    }
                    segments.add(CodeEnd(""))
                } else if (state.content.isNotBlank()) {
                    segments.add(Code(state.content, state.header.language, state.header.filePath))
                }

                segments
            }

            is ParserState.InSearch -> {
                if (state.searchContent.isNotBlank()) {
                    listOf(
                        SearchWaiting(
                            state.searchContent,
                            state.header.language,
                            state.header.filePath
                        )
                    )
                } else emptyList()
            }

            is ParserState.InReplace -> {
                if (state.replaceContent.isNotBlank()) {
                    listOf(
                        ReplaceWaiting(
                            state.searchContent,
                            state.replaceContent,
                            state.header.language,
                            state.header.filePath
                        )
                    )
                } else emptyList()
            }

            is ParserState.InThinking -> {
                val fullContent = state.content + buffer.toString()
                buffer.clear()
                if (fullContent.isNotBlank()) listOf(Thinking(fullContent))
                else emptyList()
            }
        }
    }

    private fun shouldProcessCodeFence(fenceIdx: Int, thinkStartIdx: Int): Boolean {
        return fenceIdx != -1 && (thinkStartIdx == -1 || fenceIdx < thinkStartIdx)
    }

    private fun extractTextBeforeIndex(index: Int): Text? {
        return if (index > 0) Text(buffer.substring(0, index)) else null
    }

    private fun consumeFromBuffer(length: Int) {
        buffer.delete(0, length)
    }

    private fun parseCodeHeader(headerText: String): CodeHeader? {
        val parts = headerText.split(HEADER_DELIMITER, limit = HEADER_PARTS_LIMIT)
        return if (parts.isNotEmpty()) {
            CodeHeader(
                language = parts.getOrNull(0) ?: "",
                filePath = parts.getOrNull(1)
            )
        } else null
    }

    private sealed class ParserState {
        object Outside : ParserState()

        data class CodeHeaderWaiting(
            val content: String = ""
        ) : ParserState()

        data class InCode(
            val header: CodeHeader,
            val content: String = ""
        ) : ParserState()

        data class InSearch(
            val header: CodeHeader,
            val searchContent: String = ""
        ) : ParserState()

        data class InReplace(
            val header: CodeHeader,
            val searchContent: String,
            val replaceContent: String = ""
        ) : ParserState()

        data class InThinking(
            val content: String = ""
        ) : ParserState()
    }
}
