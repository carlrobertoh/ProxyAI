package ee.carlrobert.codegpt.toolwindow.chat.parser

import java.util.regex.Matcher
import java.util.regex.Pattern

class CompleteMessageParser : MessageParser {

    companion object {
        private val CODE_BLOCK_PATTERN: Pattern =
            Pattern.compile("```([a-zA-Z0-9_+-]*)(?::([^\\n]*))?\\n(.*?)```", Pattern.DOTALL)
        private val SEARCH_REPLACE_PATTERN: Pattern =
            Pattern.compile(
                "<<<<<<< SEARCH\\n(.*?)\\n=======\\n(.*?)\\n>>>>>>> REPLACE",
                Pattern.DOTALL
            )
        private val INCOMPLETE_SEARCH_REPLACE_PATTERN: Pattern =
            Pattern.compile("<<<<<<< SEARCH\\n(.*?)(?:\\n=======\\n(.*?))?$", Pattern.DOTALL)

        private const val THINK_OPEN_TAG = "<think>"
        private const val THINK_CLOSE_TAG = "</think>\n\n"
        private const val LANGUAGE_GROUP_INDEX = 1
        private const val FILE_PATH_GROUP_INDEX = 2
        private const val CODE_CONTENT_GROUP_INDEX = 3
        private const val SEARCH_CONTENT_GROUP_INDEX = 1
        private const val REPLACE_CONTENT_GROUP_INDEX = 2

        private val TOLERANT_SEARCH_START =
            Regex("""^\s*<{3,}(\s*SEARCH.*)?$""", RegexOption.IGNORE_CASE)
        private val TOLERANT_SEPARATOR = Regex("""^\s*={3,}\s*$""")
        private val TOLERANT_REPLACE_END =
            Regex("""^\s*>{3,}(\s*REPLACE.*)?$""", RegexOption.IGNORE_CASE)
    }

    var extractedThought: String? = null
        private set

    /**
     * Parses a complete text output, extracts an optional initial thought block,
     * and identifies code blocks and regular text in the remaining content.
     *
     * @param input The full text output to parse
     * @return A list of parsed response segments (excluding the thought)
     */
    override fun parse(input: String): List<Segment> {
        val normalizedInput = input.replace("\r", "")
        val contentAfterThoughtExtraction = extractThoughtIfPresent(normalizedInput)

        return parseContentIntoSegments(contentAfterThoughtExtraction)
    }

    /**
     * Extracts thought content if present at the beginning of the input.
     * Updates the extractedThought property and returns content without the thought block.
     */
    private fun extractThoughtIfPresent(input: String): String {
        extractedThought = null

        if (!input.startsWith(THINK_OPEN_TAG)) {
            return input
        }

        val closeTagIndex = input.indexOf(THINK_CLOSE_TAG)
        return if (closeTagIndex != -1) {
            val thoughtStartIndex = THINK_OPEN_TAG.length
            extractedThought = input.substring(thoughtStartIndex, closeTagIndex).trim()
            input.substring(closeTagIndex + THINK_CLOSE_TAG.length)
        } else {
            input
        }
    }

    /**
     * Parses the content into segments, handling code blocks and text.
     */
    private fun parseContentIntoSegments(content: String): List<Segment> = buildList {
        val codeBlockMatcher = CODE_BLOCK_PATTERN.matcher(content)
        var lastProcessedIndex = 0

        while (codeBlockMatcher.find()) {
            addTextSegmentIfExists(content, lastProcessedIndex, codeBlockMatcher.start())
            addCodeBlockSegments(codeBlockMatcher)
            lastProcessedIndex = codeBlockMatcher.end()
        }

        addTextSegmentIfExists(content, lastProcessedIndex, content.length)
    }

    /**
     * Adds a text segment if there's content between the specified indices.
     */
    private fun MutableList<Segment>.addTextSegmentIfExists(
        content: String,
        startIndex: Int,
        endIndex: Int
    ) {
        if (endIndex > startIndex) {
            val textContent = content.substring(startIndex, endIndex)
            if (textContent.isNotEmpty()) {
                add(Text(textContent))
            }
        }
    }

    /**
     * Processes a code block and adds all related segments.
     */
    private fun MutableList<Segment>.addCodeBlockSegments(codeBlockMatcher: Matcher) {
        val language = codeBlockMatcher.group(LANGUAGE_GROUP_INDEX).orEmpty()
        val filePath = codeBlockMatcher.group(FILE_PATH_GROUP_INDEX)
        val codeContent = codeBlockMatcher.group(CODE_CONTENT_GROUP_INDEX).orEmpty()

        add(CodeHeader(language, filePath))
        processCodeContent(codeContent, language, filePath)
        add(CodeEnd(codeContent))
    }

    /**
     * Processes code content, handling search/replace patterns or regular code.
     */
    private fun MutableList<Segment>.processCodeContent(
        codeContent: String,
        language: String,
        filePath: String?
    ) {
        val searchReplaceSegments = extractSearchReplaceSegments(codeContent, language, filePath)

        if (searchReplaceSegments.isNotEmpty()) {
            addAll(searchReplaceSegments)
        } else {
            add(Code(codeContent, language, filePath))
        }
    }

    /**
     * Extracts search/replace segments from code content.
     * Returns empty list if no search/replace patterns are found.
     */
    private fun extractSearchReplaceSegments(
        codeContent: String,
        language: String,
        filePath: String?
    ): List<Segment> = buildList {
        val searchReplaceMatcher = SEARCH_REPLACE_PATTERN.matcher(codeContent)
        var lastProcessedIndex = 0
        var foundSearchReplace = false

        while (searchReplaceMatcher.find()) {
            foundSearchReplace = true
            addCodeSegmentIfExists(
                codeContent,
                lastProcessedIndex,
                searchReplaceMatcher.start(),
                language,
                filePath
            )
            addSearchReplaceSegment(searchReplaceMatcher, language, filePath)
            lastProcessedIndex = searchReplaceMatcher.end()
        }

        if (!foundSearchReplace) {
            val tolerantResult = tolerantScanSearchReplace(codeContent, language, filePath)
            if (tolerantResult != null) {
                addAll(tolerantResult.segments)
                lastProcessedIndex = tolerantResult.lastIndex
                foundSearchReplace = true
            }

            val incompleteMatch = findIncompleteSearchReplace(codeContent, language, filePath)
            if (incompleteMatch != null) {
                addAll(incompleteMatch.segments)
                lastProcessedIndex = incompleteMatch.endIndex
                foundSearchReplace = true
            }
        }

        if (foundSearchReplace) {
            addCodeSegmentIfExists(
                codeContent,
                lastProcessedIndex,
                codeContent.length,
                language,
                filePath
            )
        }
    }

    /**
     * Adds a code segment if there's content between the specified indices.
     */
    private fun MutableList<Segment>.addCodeSegmentIfExists(
        codeContent: String,
        startIndex: Int,
        endIndex: Int,
        language: String,
        filePath: String?
    ) {
        if (endIndex > startIndex) {
            val code = codeContent.substring(startIndex, endIndex)
            if (code.trim().isNotEmpty()) {
                add(Code(code, language, filePath))
            }
        }
    }

    /**
     * Adds a search/replace segment from the matcher.
     */
    private fun MutableList<Segment>.addSearchReplaceSegment(
        matcher: Matcher,
        language: String,
        filePath: String?
    ) {
        val searchContent = matcher.group(SEARCH_CONTENT_GROUP_INDEX).orEmpty()
        val replaceContent = matcher.group(REPLACE_CONTENT_GROUP_INDEX).orEmpty()

        add(
            SearchReplace(
                search = searchContent,
                replace = replaceContent,
                language = language,
                filePath = filePath
            )
        )
    }

    /**
     * Finds incomplete search/replace patterns and returns the segments and end index.
     */
    private fun findIncompleteSearchReplace(
        codeContent: String,
        language: String,
        filePath: String?
    ): IncompleteSearchReplaceResult? {
        val incompleteMatcher = INCOMPLETE_SEARCH_REPLACE_PATTERN.matcher(codeContent)

        return if (incompleteMatcher.find()) {
            val segments = buildList<Segment> {
                if (incompleteMatcher.start() > 0) {
                    val codeBefore = codeContent.substring(0, incompleteMatcher.start())
                    if (codeBefore.trim().isNotEmpty()) {
                        add(Code(codeBefore, language, filePath))
                    }
                }

                val searchContent = incompleteMatcher.group(SEARCH_CONTENT_GROUP_INDEX).orEmpty()
                val replaceContent = incompleteMatcher.group(REPLACE_CONTENT_GROUP_INDEX).orEmpty()

                add(
                    SearchReplace(
                        search = searchContent,
                        replace = replaceContent,
                        language = language,
                        filePath = filePath
                    )
                )
            }

            IncompleteSearchReplaceResult(segments, incompleteMatcher.end())
        } else {
            null
        }
    }

    /**
     * Data class to hold the result of incomplete search/replace processing.
     */
    private data class IncompleteSearchReplaceResult(
        val segments: List<Segment>,
        val endIndex: Int
    )

    /**
     * Tolerant scan for blocks with markers like <<<, ===, >>> (>=3 symbols).
     * Returns segments in order and the last processed index, so trailing code can be appended by caller.
     */
    private fun tolerantScanSearchReplace(
        codeContent: String,
        language: String,
        filePath: String?
    ): TolerantScanResult? {
        val segments = mutableListOf<Segment>()
        var cursor = 0
        var progressed = false

        fun findNextLineMatching(regex: Regex, startPos: Int): Pair<Int, Int> {
            var pos = startPos
            val len = codeContent.length
            while (pos <= len) {
                val lineStart = pos
                if (pos >= len) return -1 to -1
                val nl = codeContent.indexOf('\n', pos)
                val lineEndExclusive = if (nl == -1) len else nl + 1
                val rawLine = codeContent.substring(lineStart, lineEndExclusive).trimEnd('\n', '\r')
                val trimmed = rawLine.trim()
                if (regex.matches(trimmed)) return lineStart to lineEndExclusive
                pos = lineEndExclusive
            }
            return -1 to -1
        }

        while (cursor < codeContent.length) {
            val (startLineStart, startLineEnd) = findNextLineMatching(TOLERANT_SEARCH_START, cursor)
            if (startLineStart == -1) break

            segments.addCodeSegmentIfExists(codeContent, cursor, startLineStart, language, filePath)

            val (sepLineStart, sepLineEnd) = findNextLineMatching(TOLERANT_SEPARATOR, startLineEnd)
            if (sepLineStart == -1) {
                val search = codeContent.substring(startLineEnd, codeContent.length)
                if (search.isNotEmpty()) {
                    segments.add(
                        SearchReplace(
                            search = search,
                            replace = "",
                            language = language,
                            filePath = filePath
                        )
                    )
                    cursor = codeContent.length
                    progressed = true
                }
                break
            }

            val (endLineStart, endLineEnd) = findNextLineMatching(TOLERANT_REPLACE_END, sepLineEnd)
            if (endLineStart == -1) {
                val search = codeContent.substring(startLineEnd, sepLineStart)
                val replace = codeContent.substring(sepLineEnd, codeContent.length)
                segments.add(
                    SearchReplace(
                        search = search,
                        replace = replace,
                        language = language,
                        filePath = filePath
                    )
                )
                cursor = codeContent.length
                progressed = true
                break
            }

            val search = codeContent.substring(startLineEnd, sepLineStart)
            val replace = codeContent.substring(sepLineEnd, endLineStart)
            segments.add(
                SearchReplace(
                    search = search,
                    replace = replace,
                    language = language,
                    filePath = filePath
                )
            )
            cursor = endLineEnd
            progressed = true
        }

        return if (progressed) TolerantScanResult(segments, cursor) else null
    }

    private data class TolerantScanResult(
        val segments: List<Segment>,
        val lastIndex: Int
    )
}
