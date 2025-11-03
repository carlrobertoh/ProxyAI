package ee.carlrobert.codegpt.predictions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import javax.swing.JComponent

class NextEditDiffBlockComponent(
    private val editor: Editor,
    oldText: String,
    newText: String,
    private val additionsOnly: Boolean = false,
    private val acceptHintText: String? = null,
) : JComponent() {

    private val removedLines: List<String> = normalizeLines(oldText)
    private val addedLines: List<String> = normalizeLines(newText)
    private val fontMetrics: FontMetrics
    private val lineHeight: Int
    private val ascent: Int

    init {
        font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        fontMetrics = getFontMetrics(font)
        lineHeight = fontMetrics.height + JBUI.scale(2)
        ascent = fontMetrics.ascent
        isOpaque = false
        border = JBUI.Borders.empty(2)
    }

    private fun normalizeLines(text: String): List<String> {
        val cleaned = text.replace("\r", "")
        val parts = cleaned.split('\n')
        return if (cleaned.endsWith('\n') && parts.isNotEmpty()) parts.dropLast(1) else parts
    }

    override fun getPreferredSize(): Dimension {
        val maxWidthRemoved = removedLines.maxOfOrNull { fontMetrics.stringWidth(it) } ?: 0
        val maxWidthAdded = addedLines.maxOfOrNull { fontMetrics.stringWidth(it) } ?: 0
        val width = (if (additionsOnly) maxWidthAdded else maxOf(maxWidthRemoved, maxWidthAdded)) + JBUI.scale(10)
        val lines = if (additionsOnly) addedLines.size else (removedLines.size + addedLines.size)
        val height = (lines * lineHeight) + JBUI.scale(6)
        return Dimension(width, height)
    }

    override fun paint(graphics: Graphics) {
        super.paint(graphics)
        val foregroundColor = editor.colorsScheme.defaultForeground
        val componentWidth = width

        var verticalPosition = JBUI.scale(4)

        if (!additionsOnly && removedLines.isNotEmpty()) {
            val blockHeight = removedLines.size * lineHeight
            graphics.color = REMOVED_LINES_BACKGROUND
            graphics.fillRect(0, verticalPosition, componentWidth, blockHeight)
            graphics.color = REMOVED_LINES_BORDER
            graphics.fillRect(0, verticalPosition, JBUI.scale(3), blockHeight)

            var textBaselineY = verticalPosition + ascent
            for (line in removedLines) {
                graphics.color = foregroundColor
                val textX = JBUI.scale(6)
                graphics.drawString(line, textX, textBaselineY)
                graphics.color = STRIKETHROUGH_COLOR
                val strikethroughY = textBaselineY - fontMetrics.ascent / 3
                graphics.drawLine(textX, strikethroughY, textX + fontMetrics.stringWidth(line), strikethroughY)
                textBaselineY += lineHeight
            }
            verticalPosition += blockHeight
        }
        if (!additionsOnly && removedLines.isNotEmpty() && addedLines.isNotEmpty()) {
            verticalPosition += JBUI.scale(2)
        }
        if (addedLines.isNotEmpty()) {
            val blockHeight = addedLines.size * lineHeight
            graphics.color = ADDED_LINES_BACKGROUND
            graphics.fillRect(0, verticalPosition, componentWidth, blockHeight)

            acceptHintText?.let { hint ->
                val padding = JBUI.scale(6)
                val hintTextWidth = fontMetrics.stringWidth(hint)
                val hintTextX = (componentWidth - hintTextWidth - padding).coerceAtLeast(padding)
                val hintTextY = verticalPosition + padding + fontMetrics.ascent
                graphics.color = HINT_TEXT_COLOR
                graphics.drawString(hint, hintTextX, hintTextY)
            }

            var textBaselineY = verticalPosition + ascent
            for (line in addedLines) {
                graphics.color = foregroundColor
                val textX = JBUI.scale(6)
                graphics.drawString(line, textX, textBaselineY)
                textBaselineY += lineHeight
            }
        }
    }

    companion object {
        private val REMOVED_LINES_BACKGROUND = Color(220, 60, 60, 32)
        private val REMOVED_LINES_BORDER = Color(200, 20, 20, 160)
        private val STRIKETHROUGH_COLOR = Color(200, 20, 20, 180)
        private val ADDED_LINES_BACKGROUND = Color(50, 200, 90, 32)
        private val HINT_TEXT_COLOR = Color(200, 200, 200, 220)
    }
}
