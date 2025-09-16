package ee.carlrobert.codegpt.inlineedit

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.actions.editor.EditorComponentInlaysManager
import ee.carlrobert.codegpt.ui.InlineEditPopover
import ee.carlrobert.codegpt.ui.components.InlineEditChips
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import kotlin.math.abs
import kotlin.math.max

class InlineEditInlayRenderer(
    private val editor: EditorEx,
    private val project: Project
) : Disposable {
    private val logger = Logger.getInstance(InlineEditInlayRenderer::class.java)
    private var interactive: Boolean = true

    data class InlineChange(
        val startOffset: Int,
        val endOffset: Int,
        val oldText: String,
        val newText: String,
        var deletionHighlighter: RangeHighlighter? = null,
        var additionInlay: Disposable? = null,
        var buttonInlay: Disposable? = null,
        var isAccepted: Boolean = false,
        var isRejected: Boolean = false
    )

    private val changes = mutableListOf<InlineChange>()
    private val allHighlighters = mutableListOf<RangeHighlighter>()

    private data class HunkUI(
        val hunk: InlineEditSession.Hunk,
        var deletionHighlighter: RangeHighlighter? = null,
        var additionInlay: Disposable? = null,
        var buttonInlay: Disposable? = null,
    )

    private val hunkUIs = mutableListOf<HunkUI>()

    fun renderHunks(hunks: List<InlineEditSession.Hunk>) {
        runInEdt {
            hunks.forEach { renderHunk(it) }
            showTopPanel()
        }
    }

    fun replaceHunks(hunks: List<InlineEditSession.Hunk>) {
        runInEdt {
            val prev = hunkUIs.toList()
            prev.forEach { removeHunkUI(it) }
            hunks.forEach { renderHunk(it) }
            showTopPanel()
        }
    }

    private var topPanelDisposable: Disposable? = null

    private fun showTopPanel() {
        topPanelDisposable?.dispose()
        topPanelDisposable = null
    }

    fun setInteractive(enabled: Boolean) {
        interactive = enabled
    }

    private fun renderHunk(hunk: InlineEditSession.Hunk) {
        val start = hunk.baseMarker.startOffset
        val end = hunk.baseMarker.endOffset
        val baseLen = (end - start).coerceAtLeast(0)

        val deletion = if (baseLen > 0) highlightDeletion(start, end) else null

        val hasNew = hunk.proposedSlice.isNotBlank()
        val showAbove = if (baseLen > 0 && hasNew) true else baseLen == 0
        val insertionOffset = if (baseLen == 0) start else end
        val addition = if (hasNew) addInlayForAddition(
            insertionOffset,
            hunk.proposedSlice,
            showAbove = showAbove,
            onAccept = {
                editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
                    ?.accept(hunk)
            },
            onReject = {
                editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
                    ?.reject(hunk)
            }
        ) else null

        val header =
            if (!hasNew && baseLen > 0) addInlineButtons(start, hunk) else null

        hunkUIs.add(HunkUI(hunk, deletion, addition, header))
    }

    private fun highlightDeletion(startOffset: Int, endOffset: Int): RangeHighlighter? {
        try {
            val doc = editor.document
            val boundedStart = startOffset.coerceIn(0, doc.textLength)
            val inclusiveEnd =
                (endOffset - 1).coerceAtLeast(boundedStart).coerceAtMost(doc.textLength - 1)

            val startLine = doc.getLineNumber(boundedStart)
            val endLine = doc.getLineNumber(inclusiveEnd)
            val lineStart = doc.getLineStartOffset(startLine)
            val lineEnd = doc.getLineEndOffset(endLine)

            val attributes = TextAttributes().apply {
                backgroundColor = JBColor(
                    Color(255, 220, 220, 60),
                    Color(80, 40, 40, 80)
                )
                foregroundColor = null
                effectType = null
                effectColor = null
            }

            val highlighter = editor.markupModel.addRangeHighlighter(
                lineStart,
                lineEnd,
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.LINES_IN_RANGE
            )

            allHighlighters.add(highlighter)
            return highlighter
        } catch (e: Exception) {
            logger.error("Error creating deletion highlight", e)
            throw e
        }
    }

    private fun addInlayForAddition(
        offset: Int,
        newText: String,
        showAbove: Boolean = true,
        onAccept: (() -> Unit)? = null,
        onReject: (() -> Unit)? = null,
    ): Disposable? {
        try {
            val inlaysManager = EditorComponentInlaysManager.Companion.from(editor)
            val leftInset = computeLeftInsetForOffset(offset)
            val component = createAdditionComponent(newText, onAccept, onReject, leftInset)
            val lineNumber = editor.document.getLineNumber(offset)
            return inlaysManager.insert(lineNumber, component, showAbove)
        } catch (e: Exception) {
            logger.error("Error creating addition inlay", e)
            throw e
        }
    }

    private fun createAdditionComponent(
        text: String,
        onAccept: (() -> Unit)?,
        onReject: (() -> Unit)?,
        leftInset: Int,
    ): JComponent {
        val displayText = text.trimEnd('\n', '\r')

        val panel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(Color(0, 128, 0, 28), Color(0, 128, 0, 36))
            border = JBUI.Borders.empty(0, leftInset, 0, 0)
        }

        if (onAccept != null || onReject != null) {
            val header = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
            }

            fun badge(textLabel: String, bg: Color, onClick: () -> Unit): JComponent {
                return object : JComponent() {
                    init {
                        cursor = Cursor(Cursor.HAND_CURSOR)
                        addMouseListener(object : MouseAdapter() {
                            override fun mouseClicked(e: MouseEvent?) {
                                onClick()
                            }
                        })
                    }

                    override fun getPreferredSize(): Dimension {
                        val fm = getFontMetrics(font)
                        val w = max(34, fm.stringWidth(textLabel) + 14)
                        return Dimension(w, 18)
                    }

                    override fun paintComponent(g: Graphics) {
                        val g2 = g as Graphics2D
                        g2.setRenderingHint(
                            RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON
                        )
                        g2.color = Color(bg.red, bg.green, bg.blue, 200)
                        g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
                        g2.color = Color.WHITE
                        val fm = g2.fontMetrics
                        val tx = (width - fm.stringWidth(textLabel)) / 2
                        val ty = (height - fm.height) / 2 + fm.ascent
                        g2.drawString(textLabel, tx, ty)
                    }
                }
            }

            val acceptLabel = formatShortcutLabel(
                actionId = "CodeGPT.AcceptCurrentInlineEdit",
                fallback = if (SystemInfo.isMac) "⌘Y" else "Ctrl+Y"
            )
            val rejectLabel = formatShortcutLabel(
                actionId = "CodeGPT.RejectCurrentInlineEdit",
                fallback = if (SystemInfo.isMac) "⌘N" else "Ctrl+N"
            )

            onAccept?.let { header.add(badge(acceptLabel, Color(0, 153, 0), it)) }
            onReject?.let {
                header.add(
                    badge(
                        rejectLabel,
                        JBColor(Color(0xD0, 0x36, 0x36), Color(0xD0, 0x36, 0x36)),
                        it
                    )
                )
            }
            panel.add(header, BorderLayout.NORTH)
        }

        val textPane = JTextPane().apply {
            isEditable = false
            isOpaque = false
            font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            foreground = editor.colorsScheme.defaultForeground
            border = null
            margin = Insets(0, 0, 0, 0)
        }
        applySyntaxColors(displayText, textPane)
        panel.add(textPane, BorderLayout.CENTER)
        return panel
    }

    private fun formatShortcutLabel(actionId: String, fallback: String): String {
        return try {
            val keymap = KeymapManager.getInstance().activeKeymap
            val shortcuts = keymap.getShortcuts(actionId)
            val preferred = when (actionId) {
                "CodeGPT.AcceptCurrentInlineEdit" -> preferredShortcut(shortcuts, KeyEvent.VK_Y)
                "CodeGPT.RejectCurrentInlineEdit" -> preferredShortcut(shortcuts, KeyEvent.VK_N)
                else -> shortcuts.firstOrNull()
            }
            val ks = (preferred as? KeyboardShortcut)?.firstKeyStroke
            if (ks != null) keyStrokeToLabel(ks) else fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun preferredShortcut(shortcuts: Array<Shortcut>, keyCode: Int): Shortcut? {
        val macKs = KeyStroke.getKeyStroke(keyCode, InputEvent.META_DOWN_MASK)
        val winKs = KeyStroke.getKeyStroke(keyCode, InputEvent.CTRL_DOWN_MASK)
        return shortcuts.firstOrNull { (it as? KeyboardShortcut)?.firstKeyStroke == macKs }
            ?: shortcuts.firstOrNull { (it as? KeyboardShortcut)?.firstKeyStroke == winKs }
            ?: shortcuts.firstOrNull()
    }

    private fun keyStrokeToLabel(ks: KeyStroke): String {
        return if (SystemInfo.isMac) {
            buildString {
                if (ks.modifiers and InputEvent.META_DOWN_MASK != 0) append('⌘')
                if (ks.modifiers and InputEvent.SHIFT_DOWN_MASK != 0) append('⇧')
                if (ks.modifiers and InputEvent.ALT_DOWN_MASK != 0) append('⌥')
                if (ks.modifiers and InputEvent.CTRL_DOWN_MASK != 0) append('⌃')
                append(KeyEvent.getKeyText(ks.keyCode).uppercase())
            }
        } else {
            val parts = mutableListOf<String>()
            if (ks.modifiers and InputEvent.CTRL_DOWN_MASK != 0) parts.add("Ctrl")
            if (ks.modifiers and InputEvent.SHIFT_DOWN_MASK != 0) parts.add("Shift")
            if (ks.modifiers and InputEvent.ALT_DOWN_MASK != 0) parts.add("Alt")
            if (ks.modifiers and InputEvent.META_DOWN_MASK != 0) parts.add("Meta")
            parts.add(KeyEvent.getKeyText(ks.keyCode).uppercase())
            parts.joinToString("+")
        }
    }

    private fun applySyntaxColors(text: String, pane: JTextPane) {
        val fileType = editor.virtualFile?.fileType
        if (fileType == null) {
            pane.text = text; return
        }
        val highlighter =
            SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, editor.virtualFile)
        val lexer = highlighter?.highlightingLexer ?: return
        lexer.start(text)
        val doc = pane.styledDocument
        val scheme = EditorColorsManager.getInstance().globalScheme
        while (lexer.tokenType != null) {
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            val segment = text.substring(start, end)
            val keys = highlighter.getTokenHighlights(lexer.tokenType)
            val attrs = keys.firstOrNull()?.let { scheme.getAttributes(it) }
            val style = SimpleAttributeSet()
            val fg = attrs?.foregroundColor ?: editor.colorsScheme.defaultForeground
            StyleConstants.setForeground(style, fg)
            StyleConstants.setFontFamily(
                style,
                editor.colorsScheme.getFont(EditorFontType.PLAIN).family
            )
            StyleConstants.setFontSize(
                style,
                editor.colorsScheme.getFont(EditorFontType.PLAIN).size
            )
            doc.insertString(doc.length, segment, style)
            lexer.advance()
        }
    }

    private fun addInlineButtons(
        offset: Int,
        hunk: InlineEditSession.Hunk,
    ): Disposable? {
        if (!interactive) return null
        try {
            val inlaysManager = EditorComponentInlaysManager.Companion.from(editor)
            val leftInset = computeLeftInsetForOffset(offset)
            val panel = createButtonPanel(hunk, leftInset)
            val lineNumber = editor.document.getLineNumber(offset)
            return inlaysManager.insert(lineNumber, panel, true)
        } catch (e: Exception) {
            logger.error("Error creating hunk button inlay", e)
            throw e
        }
    }

    private fun createButtonPanel(
        hunk: InlineEditSession.Hunk,
        leftInset: Int = 0
    ): JComponent {
        val container = JPanel(BorderLayout()).apply {
            isOpaque = background != null
            border = JBUI.Borders.empty(0, leftInset, 0, 0)
        }
        val row = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 8, 0, 0)
        }
        val accept = InlineEditChips.keyY {
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.accept(hunk)
        }
        val reject = InlineEditChips.keyN {
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.reject(hunk)
        }
        row.add(accept)
        row.add(reject)
        container.add(row, BorderLayout.EAST)
        return container
    }

    private fun computeLeftInsetForOffset(offset: Int): Int {
        return try {
            val line = editor.document.getLineNumber(offset)
            val lineStart = editor.document.getLineStartOffset(line)
            val x = editor.offsetToXY(lineStart).x
            val gutter = editor.gutterComponentEx.width
            (x - gutter).coerceAtLeast(0)
        } catch (e: Exception) {
            logger.error("Error computing left inset for offset $offset", e)
            0
        }
    }

    private fun acceptChange(change: InlineChange) {
        if (change.isAccepted || change.isRejected) return

        runInEdt {
            WriteCommandAction.runWriteCommandAction(
                project,
                "Accept Inline Edit Change",
                "InlineEdit",
                {
                    try {
                        editor.getUserData(InlineEditPopover.Companion.POPOVER_KEY)
                            ?.markChangesAsAccepted()
                        editor.document.replaceString(
                            change.startOffset,
                            change.endOffset,
                            change.newText
                        )
                        change.copy(isAccepted = true)
                        removeChangeVisuals(change)
                    } catch (e: Exception) {
                        logger.debug("Error accepting change", e)
                    }
                })
            if (changes.isEmpty()) {
                editor.getUserData(InlineEditPopover.Companion.POPOVER_KEY)
                    ?.setInlineEditControlsVisible(false)
            }
        }
    }

    private fun rejectChange(change: InlineChange) {
        if (change.isAccepted || change.isRejected) return

        runInEdt {
            change.copy(isRejected = true)
            removeChangeVisuals(change)
            if (changes.isEmpty()) {
                editor.getUserData(InlineEditPopover.Companion.POPOVER_KEY)
                    ?.setInlineEditControlsVisible(false)
            }
        }
    }

    private fun removeHunkUI(ui: HunkUI) {
        ui.deletionHighlighter?.let { editor.markupModel.removeHighlighter(it) }
        ui.additionInlay?.dispose()
        ui.buttonInlay?.dispose()
        hunkUIs.remove(ui)
    }

    private fun removeChangeVisuals(change: InlineChange) {
        change.deletionHighlighter?.let { highlighter ->
            editor.markupModel.removeHighlighter(highlighter)
            allHighlighters.remove(highlighter)
        }

        change.additionInlay?.dispose()
        change.buttonInlay?.dispose()

        changes.remove(change)
    }

    fun acceptAll() {
        val session = editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
        if (session != null) {
            session.acceptAll()
            return
        }
        val changesToAccept = changes.filter { !it.isAccepted && !it.isRejected }
            .sortedByDescending { it.startOffset }
        changesToAccept.forEach { acceptChange(it) }
    }

    fun rejectAll() {
        val session =
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
        if (session != null) {
            session.rejectAll()
            return
        }
        val changesToReject = changes.filter { !it.isAccepted && !it.isRejected }
        changesToReject.forEach { rejectChange(it) }

        editor.getUserData(InlineEditPopover.Companion.POPOVER_KEY)
            ?.triggerPromptRestoration()
    }

    fun acceptNext() {
        val session =
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
        if (session != null) {
            session.acceptNearestToCaret()
            return
        }
        val nextChange = changes
            .filter { !it.isAccepted && !it.isRejected }
            .minByOrNull { it.startOffset }
        nextChange?.let { acceptChange(it) }
    }

    fun rejectNext() {
        val session =
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
        if (session != null) {
            val caret = editor.caretModel.offset
            val pending = hunkUIs.minByOrNull { abs(it.hunk.startOffset - caret) }
            pending?.let {
                editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.reject(it.hunk)
            }
            return
        }
        val nextChange = changes
            .filter { !it.isAccepted && !it.isRejected }
            .minByOrNull { it.startOffset }
        nextChange?.let { rejectChange(it) }
    }

    override fun dispose() {
        runInEdt {
            disposeAllHighlighters()
            disposeChanges()
            disposeHunkUIs()
            disposeTopPanel()
        }
    }

    private fun disposeAllHighlighters() {
        allHighlighters.forEach { highlighter ->
            try {
                editor.markupModel.removeHighlighter(highlighter)
            } catch (e: Exception) {
                logger.debug("Error removing highlighter during disposal", e)
            }
        }
        allHighlighters.clear()
    }

    private fun disposeChanges() {
        changes.forEach { change ->
            try {
                change.additionInlay?.dispose()
                change.buttonInlay?.dispose()
            } catch (e: Exception) {
                logger.debug("Error disposing change inlays during disposal", e)
            }
        }
        changes.clear()
    }

    private fun disposeHunkUIs() {
        hunkUIs.toList().forEach { ui ->
            try {
                ui.additionInlay?.dispose()
                ui.buttonInlay?.dispose()
                ui.deletionHighlighter?.let { editor.markupModel.removeHighlighter(it) }
            } catch (e: Exception) {
                logger.debug("Error disposing hunk UI during disposal", e)
            }
        }
        hunkUIs.clear()
    }

    private fun disposeTopPanel() {
        try {
            topPanelDisposable?.dispose()
        } catch (e: Exception) {
            logger.debug("Error disposing top panel", e)
        }
        topPanelDisposable = null
    }
}