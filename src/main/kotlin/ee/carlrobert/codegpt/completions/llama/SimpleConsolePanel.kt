package ee.carlrobert.codegpt.completions.llama

import com.intellij.openapi.application.runInEdt
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque
import javax.swing.JTextPane
import javax.swing.text.BadLocationException
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class SimpleConsolePanel : JTextPane() {

    private val lineBuffer = ConcurrentLinkedDeque<String>()
    private val backgroundColor = JBColor.namedColor("Console.background", Color(43, 43, 43))
    private val normalTextColor = JBColor.namedColor("Console.foreground", Color(204, 204, 204))
    private val errorTextColor = JBColor.namedColor("Console.errorForeground", Color(255, 102, 102))
    private val timestampColor = JBColor.namedColor("Console.grayForeground", Color(153, 153, 153))
    private val normalAttributes = SimpleAttributeSet()
    private val errorAttributes = SimpleAttributeSet()
    private val timestampAttributes = SimpleAttributeSet()

    companion object {
        private const val MAX_LINES = 1000
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    init {
        setupConsoleAppearance()
        setupStyles()
    }

    private fun setupConsoleAppearance() {
        isEditable = false
        background = backgroundColor
        foreground = normalTextColor
        font = JBUI.Fonts.create(Font.MONOSPACED, JBUI.scaleFontSize(12.0f).toInt())
        putClientProperty(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        )
        putClientProperty(
            RenderingHints.KEY_FRACTIONALMETRICS,
            RenderingHints.VALUE_FRACTIONALMETRICS_ON
        )
    }

    private fun setupStyles() {
        val baseFont = JBUI.Fonts.create(Font.MONOSPACED, JBUI.scaleFontSize(12.0f).toInt())
        val smallFont = JBUI.Fonts.create(Font.MONOSPACED, JBUI.scaleFontSize(11.0f).toInt())
        StyleConstants.setForeground(normalAttributes, normalTextColor)
        StyleConstants.setFontFamily(normalAttributes, baseFont.family)
        StyleConstants.setFontSize(normalAttributes, baseFont.size)
        StyleConstants.setForeground(errorAttributes, errorTextColor)
        StyleConstants.setFontFamily(errorAttributes, baseFont.family)
        StyleConstants.setFontSize(errorAttributes, baseFont.size)
        StyleConstants.setBold(errorAttributes, true)
        StyleConstants.setForeground(timestampAttributes, timestampColor)
        StyleConstants.setFontFamily(timestampAttributes, smallFont.family)
        StyleConstants.setFontSize(timestampAttributes, smallFont.size)
    }

    fun appendText(message: String, isError: Boolean = false) {
        try {
            val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT)
            val fullMessage = "[$timestamp] $message"
            lineBuffer.add(fullMessage)
            while (lineBuffer.size > MAX_LINES) {
                lineBuffer.pollFirst()
                removeFirstLine()
            }

            val doc = styledDocument
            val startOffset = doc.length
            doc.insertString(startOffset, "[$timestamp] ", timestampAttributes)
            val messageStyle = if (isError) errorAttributes else normalAttributes
            doc.insertString(doc.length, "$message\n", messageStyle)
            caretPosition = doc.length
        } catch (e: BadLocationException) {
            val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT)
            text = "$text[$timestamp] $message\n"
            caretPosition = text.length
        }
    }

    private fun removeFirstLine() {
        try {
            val doc = styledDocument
            val text = doc.getText(0, doc.length)
            val firstNewline = text.indexOf('\n')
            if (firstNewline >= 0) {
                doc.remove(0, firstNewline + 1)
            }
        } catch (e: BadLocationException) {
            // Ignore errors when removing lines
        }
    }

    fun clearConsole() {
        runInEdt {
            lineBuffer.clear()
            text = ""
        }
    }
}