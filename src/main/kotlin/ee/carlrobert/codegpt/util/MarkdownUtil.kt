package ee.carlrobert.codegpt.util

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import ee.carlrobert.codegpt.toolwindow.chat.ResponseNodeRenderer
import java.util.regex.Pattern

object MarkdownUtil {

    /**
     * Splits a given string into a list of strings where each element is either a code block
     * surrounded by triple backticks or a non-code block text.
     *
     * @param inputMarkdown The input markdown formatted string to be split.
     * @return A list of strings where each element is a code block or a non-code block text from the
     * input string.
     */
    @JvmStatic
    fun splitCodeBlocks(inputMarkdown: String): List<String> {
        val result: MutableList<String> = ArrayList()
        val pattern = Pattern.compile(
            """(?m)^```[a-zA-Z0-9]*\r?\n.*?\r?\n```""",
            Pattern.DOTALL
        )
        val matcher = pattern.matcher(inputMarkdown)
        var start = 0
        while (matcher.find()) {
            result.add(inputMarkdown.substring(start, matcher.start()))
            result.add(matcher.group())
            start = matcher.end()
        }
        result.add(inputMarkdown.substring(start))
        return result.stream().filter(String::isNotBlank).toList()
    }

    @JvmStatic
    fun convertMdToHtml(message: String): String {
        val options = MutableDataSet()
        options.set(HtmlRenderer.SOFT_BREAK, "<br/>")

        val document = Parser.builder(options).build().parse(message)
        return HtmlRenderer.builder(options)
            .nodeRendererFactory(ResponseNodeRenderer.Factory())
            .build()
            .render(document)
    }
}
