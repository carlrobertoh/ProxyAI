package ee.carlrobert.codegpt.actions.editor

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList


class HistoryRenderer : SimpleListCellRenderer<String>() {

    private val builder = StringBuilder()

    override fun customize(
        list: JList<out String>,
        value: String?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ) {
        if (list.width == 0 || value.isNullOrBlank()) {
            text = null
        } else {
            setRenderText(value)
        }
    }


    private fun setRenderText(value: String) {
        val text = with(builder) {
            setLength(0)


            append("<html><body><b>")
            append(trim(value))
            append("</b>")

            builder.append("</body></html>")
            toString()
        }
        setText(text)
    }

    private fun trim(value: String?): String? {
        value ?: return null

        val withoutNewLines = StringUtil.convertLineSeparators(value, "")
        return StringUtil.first(withoutNewLines, 100, /*appendEllipsis*/ true)
    }
}
