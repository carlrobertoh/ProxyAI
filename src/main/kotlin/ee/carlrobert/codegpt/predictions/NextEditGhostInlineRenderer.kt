package ee.carlrobert.codegpt.predictions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

class NextEditGhostInlineRenderer(private val text: String) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val fontMetrics = editor.component.getFontMetrics(font)
        return fontMetrics.stringWidth(text) + JBUI.scale(2)
    }

    override fun paint(
        inlay: Inlay<*>,
        graphics: Graphics2D,
        target: Rectangle2D,
        textAttributes: TextAttributes
    ) {
        val editor: Editor = inlay.editor
        graphics.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val metrics = graphics.fontMetrics
        val startX = (target.x + JBUI.scale(1)).toInt()
        val startY = (target.y + (target.height - metrics.height) / 2 + metrics.ascent).toInt()

        val foregroundColor = editor.colorsScheme.defaultForeground
        graphics.color =
            Color(foregroundColor.red, foregroundColor.green, foregroundColor.blue, 120)
        graphics.drawString(text, startX, startY)
    }
}
