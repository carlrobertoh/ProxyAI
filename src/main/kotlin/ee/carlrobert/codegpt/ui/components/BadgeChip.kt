package ee.carlrobert.codegpt.ui.components

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * A small rounded "pill" button used in inline edit UIs.
 * Matches the visual feel of the Y/N badges shown in diff blocks.
 */
class BadgeChip(
    private val text: String,
    private val backgroundColor: JBColor,
    private val onClick: () -> Unit,
    private val fixedHeight: Int = JBUI.scale(18),
    private val horizontalPadding: Int = JBUI.scale(8),
    private val cornerRadius: Int = JBUI.scale(8),
    private val textColor: JBColor = JBColor(Color(0xDF, 0xE1, 0xE5), Color(0xDF, 0xE1, 0xE5))
) : JComponent() {

    init {
        cursor = Cursor(Cursor.HAND_CURSOR)
        toolTipText = text
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                onClick()
            }
        })
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val w = fm.stringWidth(text) + horizontalPadding * 2
        return Dimension(w, fixedHeight)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val r = Rectangle(0, 0, width, height)

        g2.color = backgroundColor
        g2.fillRoundRect(r.x, r.y, r.width - 1, r.height - 1, cornerRadius, cornerRadius)
        g2.color = backgroundColor.darker()
        g2.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, cornerRadius, cornerRadius)

        g2.font = font
        g2.color = textColor
        val fm = g2.fontMetrics
        val tx = (width - fm.stringWidth(text)) / 2
        val ty = (height - fm.height) / 2 + fm.ascent
        g2.drawString(text, tx, ty)
    }
}
