package ee.carlrobert.codegpt.ui.textarea

object PromptTextFieldConstants {
    const val SEARCH_DELAY_MS = 200L
    const val LOOKUP_LOADING_REVEAL_MS = 120L
    const val LOOKUP_LOADING_MIN_VISIBLE_MS = 180L
    const val MAX_SEARCH_RESULTS = 100
    const val MIN_VISIBLE_LINES = 2
    const val DEFAULT_TOOL_WINDOW_HEIGHT = 400
    const val BORDER_PADDING = 4
    const val BORDER_SIDE_PADDING = 8
    const val HEIGHT_PADDING = 8
    const val PASTE_PLACEHOLDER_MIN_LENGTH = 250

    val DEFAULT_GROUP_NAMES = listOf(
        "files", "file", "f",
        "git", "g",
        "conversations", "conversation", "conv", "c",
        "history", "hist", "h",
        "personas", "persona", "p",
        "docs", "doc", "d",
        "diagnostics", "diagnostic", "diag",
        "mcp", "m",
        "web", "w",
        "image", "img", "i"
    )

    const val AT_SYMBOL = "@"
    const val SPACE = " "
    const val NEWLINE = "\n"
}
