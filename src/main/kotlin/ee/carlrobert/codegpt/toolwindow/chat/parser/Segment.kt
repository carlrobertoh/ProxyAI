package ee.carlrobert.codegpt.toolwindow.chat.parser

sealed class Segment(
    open val content: String = "",
    open val language: String = "",
    open val filePath: String? = null
)

data class Text(override val content: String) : Segment(content)
data class Thinking(override val content: String) : Segment(content)
data class CodeHeader(
    override val language: String,
    override val filePath: String?
) : Segment("", language, filePath)

data class CodeHeaderWaiting(val partial: String) : Segment(partial)
data class Code(
    override val content: String,
    override val language: String,
    override val filePath: String?
) : Segment(content, language, filePath)

data class CodeEnd(override val content: String) : Segment(content)
data class SearchWaiting(
    val search: String,
    override val language: String,
    override val filePath: String?
) : Segment(search, language, filePath)

data class ReplaceWaiting(
    val search: String,
    val replace: String,
    override val language: String,
    override val filePath: String?
) : Segment(replace, language, filePath)

data class SearchReplace(
    val search: String,
    val replace: String,
    override val language: String,
    override val filePath: String?
) : Segment(search, language, filePath)