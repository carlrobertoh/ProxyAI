package ee.carlrobert.codegpt.toolwindow.chat.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.toolwindow.visualizer.MermaidInputParser
import ee.carlrobert.codegpt.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager

class MermaidResponsePanel : BorderLayoutPanel(), Disposable {

    private val browser: JBCefBrowser?
    private val renderDebounceTimer: Timer = Timer(RENDER_DEBOUNCE_MS) { flushRender(force = false) }.apply {
        isRepeats = false
    }

    private var dragStartYOnScreen = 0
    private var dragStartHeight = 0
    private var latestMermaidSource = EMPTY_STATE_DIAGRAM
    private var latestDiagramTypeLabel = "Flowchart"
    private var lastLoadedMermaidSource: String? = null
    private var lastLoadedDiagramTypeLabel: String? = null

    init {
        border = JBUI.Borders.empty(4, 0)
        applyPreferredHeight(sharedPreferredHeight)
        isOpaque = false

        if (!isSupported()) {
            browser = null
            addToCenter(
                UIUtil.createTextPane(
                    "<html><p style=\"margin: 0;\">Mermaid preview unavailable in this IDE build.</p></html>",
                    false
                )
            )
        } else {
            browser = JBCefBrowser("about:blank")
            addToCenter(browser.component)
            addToBottom(createResizeHandle())
            addHierarchyListener { event ->
                if ((event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) == 0L) {
                    return@addHierarchyListener
                }
                if (isShowing) {
                    flushRender(force = true)
                }
            }
        }
    }

    fun render(source: String) {
        if (browser == null) {
            return
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { render(source) }
            return
        }

        var mermaidSource = MermaidInputParser.sanitizeForRender(source)
        if (mermaidSource.isBlank()) {
            mermaidSource = EMPTY_STATE_DIAGRAM
        }
        latestMermaidSource = normalizeMermaidSource(mermaidSource)
        latestDiagramTypeLabel = MermaidInputParser.detectDiagramTypeLabel(latestMermaidSource)
        renderDebounceTimer.restart()
    }

    private fun flushRender(force: Boolean) {
        val browser = browser ?: return
        if (!force
            && latestMermaidSource == lastLoadedMermaidSource
            && latestDiagramTypeLabel == lastLoadedDiagramTypeLabel
        ) {
            return
        }

        val html = MermaidResponseHtmlTemplate.render(
            source = latestMermaidSource,
            diagramTypeLabel = latestDiagramTypeLabel,
            theme = resolveThemePalette()
        )
        browser.loadHTML(html)
        lastLoadedMermaidSource = latestMermaidSource
        lastLoadedDiagramTypeLabel = latestDiagramTypeLabel
    }

    override fun dispose() {
        renderDebounceTimer.stop()
        browser?.let(Disposer::dispose)
    }

    private fun createResizeHandle(): JPanel {
        val handle = JPanel().apply {
            isOpaque = true
            background = resolveHandleColor()
            preferredSize = JBUI.size(10, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
            toolTipText = "Drag to resize Mermaid preview height"
        }

        val mouseAdapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return
                }
                dragStartYOnScreen = e.yOnScreen
                dragStartHeight = preferredSize.height
            }

            override fun mouseDragged(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return
                }
                val delta = e.yOnScreen - dragStartYOnScreen
                val nextHeight = clampHeight(dragStartHeight + delta)
                sharedPreferredHeight = nextHeight
                applyPreferredHeight(nextHeight)
            }
        }

        handle.addMouseListener(mouseAdapter)
        handle.addMouseMotionListener(mouseAdapter)
        return handle
    }

    private fun applyPreferredHeight(height: Int) {
        preferredSize = Dimension(10, clampHeight(height))
        revalidate()
        repaint()
    }

    private companion object {
        private const val DEFAULT_HEIGHT = 300
        private const val MIN_HEIGHT = 180
        private const val MAX_HEIGHT = 1000
        private const val RENDER_DEBOUNCE_MS = 120
        private const val MAX_MERMAID_SOURCE_CHARS = 120_000
        private const val EMPTY_STATE_DIAGRAM = """
            flowchart TD
              A[Mermaid diagram] --> B[No content yet]
        """
        private const val OVERSIZE_DIAGRAM = """
            flowchart TD
              A[Mermaid preview truncated] --> B[Diagram is too large for inline preview]
              B --> C[Split into smaller diagrams]
        """

        @JvmStatic
        fun isSupported(): Boolean = JBCefApp.isSupported()

        private var sharedPreferredHeight = DEFAULT_HEIGHT

        private fun normalizeMermaidSource(source: String): String {
            return if (source.length <= MAX_MERMAID_SOURCE_CHARS) source else OVERSIZE_DIAGRAM
        }

        private fun resolveThemePalette(): MermaidThemePalette {
            val foreground = JBColor.namedColor(
                "Label.foreground",
                fallbackColor(UIManager.getColor("Label.foreground"), JBColor.foreground())
            )
            val panelBackground = JBColor.namedColor(
                "Panel.background",
                fallbackColor(UIManager.getColor("Panel.background"), JBColor(0xFFFFFF, 0x2B2D30))
            )
            val errorColor = JBColor.namedColor(
                "ValidationTooltip.errorForeground",
                fallbackColor(UIManager.getColor("Component.errorFocusColor"), JBColor(0xC62828, 0xF28B82))
            )

            val hudBackground = withAlpha(ColorUtil.mix(panelBackground, foreground, 0.08), 0.92)
            val hudBorder = withAlpha(ColorUtil.mix(panelBackground, foreground, 0.26), 0.9)
            val buttonBackground = withAlpha(ColorUtil.mix(panelBackground, foreground, 0.12), 0.97)
            val buttonBackgroundHover = withAlpha(ColorUtil.mix(panelBackground, foreground, 0.18), 0.98)
            val buttonBorder = withAlpha(ColorUtil.mix(panelBackground, foreground, 0.30), 0.98)
            val mutedForeground = withAlpha(ColorUtil.mix(foreground, panelBackground, 0.40), 0.95)

            val labelFont = fallbackFont(UIManager.getFont("Label.font"))
            val mermaidTheme = if (ColorUtil.isDark(panelBackground)) "dark" else "default"

            return MermaidThemePalette(
                foreground = toCssColor(foreground),
                mutedForeground = toCssColor(mutedForeground),
                hudBackground = toCssColor(hudBackground),
                hudBorder = toCssColor(hudBorder),
                buttonBackground = toCssColor(buttonBackground),
                buttonBackgroundHover = toCssColor(buttonBackgroundHover),
                buttonBorder = toCssColor(buttonBorder),
                error = toCssColor(errorColor),
                fontFamily = toCssFontFamily(labelFont),
                fontSize = "${labelFont.size}px",
                mermaidTheme = mermaidTheme
            )
        }

        private fun fallbackColor(value: Color?, fallback: Color): Color = value ?: fallback

        private fun fallbackFont(font: Font?): Font = font ?: Font("Dialog", Font.PLAIN, 12)

        private fun withAlpha(color: Color, alphaFraction: Double): Color {
            val alpha = (alphaFraction.coerceIn(0.0, 1.0) * 255).toInt()
            return Color(color.red, color.green, color.blue, alpha)
        }

        private fun toCssColor(color: Color): String {
            val alpha = color.alpha / 255.0
            val alphaText = String.format(Locale.US, "%.3f", alpha)
            return "rgba(${color.red}, ${color.green}, ${color.blue}, $alphaText)"
        }

        private fun toCssFontFamily(font: Font): String {
            val family = font.family.replace("\"", "\\\"")
            return "\"$family\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif"
        }

        private fun clampHeight(height: Int): Int = height.coerceIn(MIN_HEIGHT, MAX_HEIGHT)

        private fun resolveHandleColor(): Color {
            return JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
        }
    }
}
