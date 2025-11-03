package ee.carlrobert.codegpt.predictions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D

class NextEditTagInlineRenderer(private val text: String) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val fontMetrics = editor.component.getFontMetrics(font)
        val horizontalPadding = JBUI.scale(8)
        val textWidth = fontMetrics.stringWidth(text)
        return textWidth + horizontalPadding * 2
    }

    override fun paint(
        inlay: Inlay<*>,
        graphics: Graphics2D,
        target: Rectangle2D,
        textAttributes: TextAttributes
    ) {
        val editor: Editor = inlay.editor
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        graphics.font = font
        val fontMetrics = graphics.fontMetrics

        val horizontalPadding = JBUI.scale(8)
        val verticalPadding = JBUI.scale(2)
        val cornerRadius = JBUI.scale(8)

        val accentColor = JBUI.CurrentTheme.Focus.defaultButtonColor()
        val backgroundColor = Color(accentColor.red, accentColor.green, accentColor.blue, 48)

        val rectangleX = target.x.toInt()
        val rectangleY =
            (target.y + (target.height - (fontMetrics.height + verticalPadding * 2)) / 2).toInt()
        val rectangleWidth = calcWidthInPixels(inlay)
        val rectangleHeight = fontMetrics.height + verticalPadding * 2

        graphics.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON
        )
        graphics.color = backgroundColor
        graphics.fillRoundRect(
            rectangleX,
            rectangleY,
            rectangleWidth,
            rectangleHeight,
            cornerRadius,
            cornerRadius
        )
        graphics.color = accentColor
        graphics.drawRoundRect(
            rectangleX,
            rectangleY,
            rectangleWidth,
            rectangleHeight,
            cornerRadius,
            cornerRadius
        )

        val textStartX = rectangleX + horizontalPadding
        val textBaselineY = rectangleY + verticalPadding + fontMetrics.ascent
        graphics.color = Color.WHITE
        graphics.drawString(text, textStartX, textBaselineY)
    }
}
