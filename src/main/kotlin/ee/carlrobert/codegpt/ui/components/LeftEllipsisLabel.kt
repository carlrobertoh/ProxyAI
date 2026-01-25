package ee.carlrobert.codegpt.ui.components

import com.intellij.ui.components.JBLabel
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/**
 * A label that keeps the *end* of the text visible by eliding from the left.
 */
class LeftEllipsisLabel(text: String = "") : JBLabel() {

    var fullText: String = text
        set(value) {
            field = value
            updateDisplayedText()
        }

    init {
        super.setText(text)
        fullText = text
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                updateDisplayedText()
            }
        })
    }

    override fun setText(text: String?) {
        fullText = text.orEmpty()
    }

    override fun doLayout() {
        super.doLayout()
        updateDisplayedText()
    }

    override fun getMinimumSize(): Dimension {
        val size = super.getMinimumSize()
        return Dimension(0, size.height)
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        val fm = getFontMetrics(font) ?: return size
        val ellipsisWidth = fm.stringWidth("...")
        val maxWidth = fm.stringWidth(fullText)
        return Dimension(minOf(size.width, maxWidth + ellipsisWidth), size.height)
    }

    private fun updateDisplayedText() {
        val availableWidth = width
        if (availableWidth <= 0) {
            super.setText(fullText)
            return
        }

        val fm = getFontMetrics(font) ?: return
        super.setText(leftEllipsize(fullText, fm, availableWidth))
        toolTipText = fullText
    }

    private fun leftEllipsize(text: String, fm: FontMetrics, maxWidth: Int): String {
        if (text.isEmpty()) return text
        if (fm.stringWidth(text) <= maxWidth) return text

        val ellipsis = "..."
        val ellipsisWidth = fm.stringWidth(ellipsis)
        if (ellipsisWidth >= maxWidth) {
            return ""
        }

        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val candidate = ellipsis + text.substring(mid)
            if (fm.stringWidth(candidate) <= maxWidth) {
                hi = mid
            } else {
                lo = mid + 1
            }
        }

        val startIndex = lo.coerceIn(0, text.length)
        val result = ellipsis + text.substring(startIndex)
        if (fm.stringWidth(result) <= maxWidth) return result

        for (i in startIndex + 1..text.length) {
            val r = ellipsis + text.substring(i)
            if (fm.stringWidth(r) <= maxWidth) return r
        }

        return ""
    }
}
