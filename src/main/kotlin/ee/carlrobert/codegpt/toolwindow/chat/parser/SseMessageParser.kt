package ee.carlrobert.codegpt.toolwindow.chat.parser

enum class State { OUTSIDE, CODE_HEADER_WAITING, IN_CODE, IN_SEARCH, IN_REPLACE, IN_THINKING }

class SseMessageParser : MessageParser {

    var state = State.OUTSIDE
    private val buffer = StringBuilder()
    private val parsedSegments = mutableListOf<Segment>()

    private var currentCodeHeader: CodeHeader? = null
    private val codeBuilder = StringBuilder()
    private val headerBuilder = StringBuilder()
    private val searchBuilder = StringBuilder()
    private val replaceBuilder = StringBuilder()
    private val thinkingBuilder = StringBuilder()

    fun clear() {
        state = State.OUTSIDE
        buffer.clear()
        parsedSegments.clear()
        currentCodeHeader = null
        codeBuilder.clear()
        headerBuilder.clear()
        searchBuilder.clear()
        replaceBuilder.clear()
        thinkingBuilder.clear()
    }

    /**
     * Parse incoming partial text and return any completed segments.
     * Leftover text remains in buffer until more input arrives.
     */
    override fun parse(input: String): List<Segment> {
        buffer.append(input)
        val output = mutableListOf<Segment>()

        loop@ while (true) {
            when (state) {
                State.OUTSIDE -> {
                    val fenceIdx = buffer.indexOf("```")
                    val thinkStartIdx = buffer.indexOf("<think>")

                    when {
                        fenceIdx != -1 && (thinkStartIdx == -1 || fenceIdx < thinkStartIdx) -> {
                            if (fenceIdx > 0) {
                                output += Text(buffer.substring(0, fenceIdx))
                            }
                            buffer.delete(0, fenceIdx + 3)
                            state = State.CODE_HEADER_WAITING
                            headerBuilder.clear()
                            continue@loop
                        }

                        thinkStartIdx != -1 -> {
                            if (thinkStartIdx > 0) {
                                output += Text(buffer.substring(0, thinkStartIdx))
                            }
                            buffer.delete(0, thinkStartIdx + "<think>".length)
                            state = State.IN_THINKING
                            thinkingBuilder.clear()
                            continue@loop
                        }

                        else -> break@loop
                    }
                }

                State.CODE_HEADER_WAITING -> {
                    val nlIdx = buffer.indexOf("\n")
                    if (nlIdx < 0) break@loop
                    val headerLine = buffer.substring(0, nlIdx).trim()
                    buffer.delete(0, nlIdx + 1)
                    headerBuilder.append(headerLine)

                    val headerText = headerBuilder.toString()
                    val parts = headerText.split(":", limit = 2)

                    val language = parts.getOrNull(0) ?: ""
                    val fileName = parts.getOrNull(1)

                    if (parts.size > 0) {
                        currentCodeHeader = CodeHeader(language, fileName)
                        output += currentCodeHeader!!
                        state = State.IN_CODE
                        codeBuilder.clear()
                        headerBuilder.clear()
                    } else {
                        output += CodeHeaderWaiting(headerText)
                    }
                }

                State.IN_CODE -> {
                    val idx = buffer.indexOf("\n")
                    if (idx < 0) break@loop
                    val line = buffer.substring(0, idx)
                    buffer.delete(0, idx + 1)
                    when {
                        line.trim() == "```" -> {
                            if (codeBuilder.isNotEmpty()) {
                                output += Code(
                                    codeBuilder.toString(),
                                    currentCodeHeader!!.language,
                                    currentCodeHeader!!.filePath
                                )
                            }
                            output += CodeEnd("")
                            state = State.OUTSIDE
                        }

                        line.trimStart().startsWith("<<<<<<< SEARCH") -> {
                            state = State.IN_SEARCH
                            searchBuilder.clear()
                            output += SearchWaiting(
                                "",
                                currentCodeHeader!!.language,
                                currentCodeHeader!!.filePath
                            )
                        }

                        else -> codeBuilder.appendLine(line)
                    }
                }

                State.IN_SEARCH -> {
                    val idx = buffer.indexOf("\n")
                    if (idx < 0) break@loop
                    val line = buffer.substring(0, idx)
                    buffer.delete(0, idx + 1)
                    if (line.trim() == "=======") {
                        state = State.IN_REPLACE
                        replaceBuilder.clear()
                        output += ReplaceWaiting(
                            searchBuilder.toString(),
                            "",
                            currentCodeHeader!!.language,
                            currentCodeHeader!!.filePath
                        )
                    } else {
                        searchBuilder.appendLine(line)
                        output += SearchWaiting(
                            searchBuilder.toString(),
                            currentCodeHeader!!.language,
                            currentCodeHeader!!.filePath
                        )
                    }
                }

                State.IN_REPLACE -> {
                    val idx = buffer.indexOf("\n")
                    if (idx < 0) break@loop
                    val line = buffer.substring(0, idx)
                    buffer.delete(0, idx + 1)
                    if (line.trim().startsWith(">>>>>>> REPLACE")) {
                        output += SearchReplace(
                            search = searchBuilder.toString(),
                            replace = replaceBuilder.toString(),
                            language = currentCodeHeader!!.language,
                            filePath = currentCodeHeader!!.filePath
                        )
                        state = State.IN_CODE
                    } else {
                        replaceBuilder.appendLine(line)
                        output += ReplaceWaiting(
                            searchBuilder.toString(),
                            replaceBuilder.toString(),
                            currentCodeHeader!!.language,
                            currentCodeHeader!!.filePath
                        )
                    }
                }

                State.IN_THINKING -> {
                    val thinkEndIdx = buffer.indexOf("</think>")
                    if (thinkEndIdx < 0) {
                        if (buffer.isNotEmpty()) {
                            thinkingBuilder.append(buffer)
                            output += Thinking(thinkingBuilder.toString())
                            buffer.clear()
                        }
                        break@loop
                    }

                    thinkingBuilder.append(buffer.substring(0, thinkEndIdx))
                    output += Thinking(thinkingBuilder.toString())
                    buffer.delete(0, thinkEndIdx + "</think>".length)
                    state = State.OUTSIDE
                    thinkingBuilder.clear()
                    continue@loop
                }
            }
        }

        when (state) {
            State.OUTSIDE ->
                if (buffer.isNotBlank())
                    output += Text(buffer.toString())

            State.CODE_HEADER_WAITING ->
                if (headerBuilder.isNotBlank())
                    output += CodeHeaderWaiting(headerBuilder.toString())

            State.IN_CODE ->
                if (codeBuilder.isNotBlank())
                    output += Code(
                        codeBuilder.toString(),
                        currentCodeHeader!!.language,
                        currentCodeHeader!!.filePath
                    )

            State.IN_SEARCH ->
                if (searchBuilder.isNotBlank())
                    output += SearchWaiting(
                        searchBuilder.toString(),
                        currentCodeHeader!!.language,
                        currentCodeHeader!!.filePath
                    )

            State.IN_REPLACE ->
                if (replaceBuilder.isNotBlank())
                    output += ReplaceWaiting(
                        searchBuilder.toString(),
                        replaceBuilder.toString(),
                        currentCodeHeader!!.language,
                        currentCodeHeader!!.filePath
                    )

            State.IN_THINKING ->
                if (thinkingBuilder.isNotBlank() || buffer.isNotBlank()) {
                    thinkingBuilder.append(buffer)
                    buffer.clear()
                    output += Thinking(thinkingBuilder.toString())
                }
        }

        parsedSegments.addAll(output)
        return output
    }
}