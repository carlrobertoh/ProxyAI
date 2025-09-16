package ee.carlrobert.codegpt.inlineedit

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.EmptyAction
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.TextRange
import com.intellij.ui.components.JBScrollPane
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.inlineedit.InlineEditInlayRenderer
import ee.carlrobert.codegpt.inlineedit.InlineEditKeyEventDispatcher
import ee.carlrobert.codegpt.ui.InlineEditPopover
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import kotlin.math.abs

class InlineEditSession(
    private val project: Project,
    private val editor: EditorEx,
    private val baseRange: TextRange,
    private val initialBaseText: String,
    private var proposedText: String
) : Disposable {

    data class Hunk(
        val baseMarker: RangeMarker,
        val proposedSlice: String,
        val startOffset: Int,
        val endOffset: Int,
        var accepted: Boolean = false,
        var rejected: Boolean = false
    )

    private val renderer = InlineEditInlayRenderer(editor, project)
    private val hunks = mutableListOf<Hunk>()
    private val lockedRanges = mutableListOf<RangeMarker>()
    private val rejectedRanges = mutableListOf<RangeMarker>()
    private val rootMarker: RangeMarker = runReadAction {
        editor.document.createRangeMarker(baseRange.startOffset, baseRange.endOffset, true).apply {
            isGreedyToLeft = true
            isGreedyToRight = true
        }
    }

    init {
        buildAndRenderHunks()

        editor.putUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION, this)
        editor.putUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER, renderer)

        registerEditorScopedShortcuts()
        InlineEditKeyEventDispatcher(
            project,
            editor,
            onAccept = { acceptNearestToCaret() },
            onReject = { rejectNearestToCaret() }
        ).register(this)
    }

    private fun buildAndRenderHunks() {
        val newHunks = computeHunks()
        hunks.clear()
        hunks.addAll(newHunks)
        renderer.renderHunks(hunks)
    }

    private fun computeHunks(): List<Hunk> {
        val (baseNow, baseStartOffset) = runReadAction {
            val start = rootMarker.startOffset.coerceAtLeast(0)
            val end =
                rootMarker.endOffset.coerceAtLeast(start).coerceAtMost(editor.document.textLength)
            Pair(editor.document.getText(TextRange(start, end)), start)
        }

        val lineFragments = ComparisonManager.getInstance()
            .compareLines(baseNow, proposedText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
        if (lineFragments.isEmpty()) return emptyList()

        val baseLineOffsets = computeLineStartOffsets(baseNow)
        val proposedLineOffsets = computeLineStartOffsets(proposedText)

        val rawHunks = runReadAction {
            val list = mutableListOf<Hunk>()
            val docLength = editor.document.textLength
            for (frag in lineFragments) {
                val baseStart =
                    if (frag.startLine1 < baseLineOffsets.size) baseLineOffsets[frag.startLine1] else baseNow.length
                val baseEnd =
                    if (frag.endLine1 < baseLineOffsets.size) baseLineOffsets[frag.endLine1] else baseNow.length
                val proposedStart =
                    if (frag.startLine2 < proposedLineOffsets.size) proposedLineOffsets[frag.startLine2] else proposedText.length
                val proposedEnd =
                    if (frag.endLine2 < proposedLineOffsets.size) proposedLineOffsets[frag.endLine2] else proposedText.length

                val oldSlice = safeSlice(baseNow, baseStart, baseEnd)
                val newSlice = safeSlice(proposedText, proposedStart, proposedEnd)
                if (oldSlice == newSlice) continue

                val rawStart = baseStartOffset + baseStart
                val rawEnd = baseStartOffset + baseEnd
                val start = rawStart.coerceIn(0, docLength)
                val end = rawEnd.coerceIn(start, docLength)

                val marker = editor.document.createRangeMarker(start, end, true).apply {
                    isGreedyToLeft = true
                    isGreedyToRight = true
                }
                list.add(Hunk(marker, newSlice, start, end))
            }
            list
        }
        return rawHunks.filter { h ->
            lockedRanges.none { lock ->
                rangesOverlap(
                    h.startOffset,
                    h.endOffset,
                    lock.startOffset,
                    lock.endOffset
                )
            } &&
                    rejectedRanges.none { rej ->
                        rangesOverlap(
                            h.startOffset,
                            h.endOffset,
                            rej.startOffset,
                            rej.endOffset
                        )
                    }
        }
    }

    fun updateProposedText(newText: String, interactive: Boolean) {
        this.proposedText = newText
        val newHunks = computeHunks()
        hunks.clear()
        hunks.addAll(newHunks)
        renderer.setInteractive(interactive)
        renderer.replaceHunks(hunks)
    }

    fun acceptNearestToCaret() {
        val caret = editor.caretModel.offset
        val next = hunks
            .filter { !it.accepted && !it.rejected }
            .minByOrNull { abs(it.startOffset - caret) }
        if (next != null) acceptHunk(next)
    }

    fun rejectNearestToCaret() {
        val caret = editor.caretModel.offset
        val next = hunks
            .filter { !it.accepted && !it.rejected }
            .minByOrNull { abs(it.startOffset - caret) }
        if (next != null) rejectHunk(next)
    }

    fun acceptAll() {
        editor.getUserData(InlineEditPopover.Companion.POPOVER_KEY)?.markChangesAsAccepted()

        hunks
            .filter { !it.accepted && !it.rejected }
            .sortedByDescending { it.baseMarker.startOffset }
            .forEach { acceptHunk(it) }
        removeCompareLinkIfAny()
        dispose()
    }

    fun rejectAll() {
        hunks
            .filter { !it.accepted && !it.rejected }
            .forEach { rejectHunk(it) }
        removeCompareLinkIfAny()

        editor.getUserData(InlineEditPopover.Companion.POPOVER_KEY)
            ?.triggerPromptRestoration()

        dispose()
    }

    private fun acceptHunk(hunk: Hunk) {
        if (hunk.accepted || hunk.rejected) return
        val start = hunk.baseMarker.startOffset
        val end = hunk.baseMarker.endOffset
        WriteCommandAction.runWriteCommandAction(project) {
            editor.getUserData(InlineEditPopover.Companion.POPOVER_KEY)?.markChangesAsAccepted()
            editor.document.replaceString(start, end, hunk.proposedSlice)
            hunk.accepted = true
            val newEnd = start + hunk.proposedSlice.length
            val lock = editor.document.createRangeMarker(start, newEnd, true).apply {
                isGreedyToLeft = true
                isGreedyToRight = true
            }
            lockedRanges.add(lock)
            val newHunks = computeHunks()
            hunks.clear()
            hunks.addAll(newHunks)
            renderer.replaceHunks(hunks)
            if (hunks.isEmpty()) {
                editor.getUserData(InlineEditPopover.Companion.POPOVER_KEY)
                    ?.setInlineEditControlsVisible(false)
                removeCompareLinkIfAny()
            }
        }
    }

    private fun rejectHunk(hunk: Hunk) {
        if (hunk.accepted || hunk.rejected) return

        hunk.rejected = true

        val start = hunk.baseMarker.startOffset
        val end = hunk.baseMarker.endOffset
        val safeStart = start.coerceIn(0, editor.document.textLength)
        val safeEnd = end.coerceIn(safeStart, editor.document.textLength)
        val marker = editor.document.createRangeMarker(safeStart, safeEnd, true).apply {
            isGreedyToLeft = true
            isGreedyToRight = true
        }
        rejectedRanges.add(marker)

        val newHunks = computeHunks()
        hunks.clear()
        hunks.addAll(newHunks)

        renderer.replaceHunks(hunks)
        if (hunks.isEmpty()) {
            editor.getUserData(InlineEditPopover.Companion.POPOVER_KEY)
                ?.setInlineEditControlsVisible(false)
            removeCompareLinkIfAny()
        }
    }

    fun accept(hunk: Hunk) = acceptHunk(hunk)
    fun reject(hunk: Hunk) = rejectHunk(hunk)
    fun setInteractive(enabled: Boolean) = renderer.setInteractive(enabled)

    fun hasPendingHunks(): Boolean {
        return hunks.any { !it.accepted && !it.rejected }
    }

    private fun rangesOverlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean {
        val start = maxOf(aStart, bStart)
        val end = minOf(aEnd, bEnd)
        return start < end
    }

    override fun dispose() {
        editor.putUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION, null)
        editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER)?.dispose()
        editor.putUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_RENDERER, null)
        editor.getUserData(InlineEditPopover.Companion.POPOVER_KEY)?.setInlineEditControlsVisible(false)

        removeCompareLinkIfAny()
    }

    private fun removeCompareLinkIfAny() {
        val comp = editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_COMPARE_LINK) ?: return
        val statusComponent = (editor.scrollPane as JBScrollPane).statusComponent
        statusComponent.remove(comp)
        statusComponent.revalidate()
        statusComponent.repaint()
        editor.putUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_COMPARE_LINK, null)
    }

    private fun computeLineStartOffsets(text: String): IntArray {
        val lines = text.split('\n')
        val offsets = IntArray(lines.size + 1)
        var sum = 0
        for (i in lines.indices) {
            offsets[i] = sum
            sum += lines[i].length + 1
        }
        offsets[lines.size] = sum
        return offsets
    }

    private fun safeSlice(text: String, start: Int, end: Int): String {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        return text.substring(s, e)
    }

    companion object {
        fun start(
            project: Project,
            editor: EditorEx,
            baseRange: TextRange,
            baseText: String,
            proposedText: String
        ): InlineEditSession {
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.dispose()
            return InlineEditSession(project, editor, baseRange, baseText, proposedText)
        }
    }

    private fun registerEditorScopedShortcuts() {
        val am = ActionManager.getInstance()
        am.getAction("CodeGPT.AcceptCurrentInlineEdit")?.let { action ->
            val ks = resolvePreferredKeyStroke("CodeGPT.AcceptCurrentInlineEdit", KeyEvent.VK_Y)
            val wrapped = EmptyAction.wrap(action)
            wrapped.registerCustomShortcutSet(
                CustomShortcutSet(KeyboardShortcut(ks, null)),
                editor.contentComponent,
                this
            )
        }
        am.getAction("CodeGPT.RejectCurrentInlineEdit")?.let { action ->
            val ks = resolvePreferredKeyStroke("CodeGPT.RejectCurrentInlineEdit", KeyEvent.VK_N)
            val wrapped = EmptyAction.wrap(action)
            wrapped.registerCustomShortcutSet(
                CustomShortcutSet(KeyboardShortcut(ks, null)),
                editor.contentComponent,
                this
            )
        }

        am.getAction("codegpt.acceptInlineEdit")?.let { editorAction ->
            val metaEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK)
            val ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)
            val shortcuts = arrayOf(
                KeyboardShortcut(metaEnter, null),
                KeyboardShortcut(ctrlEnter, null)
            )
            val wrapped = EmptyAction.wrap(editorAction)
            wrapped.registerCustomShortcutSet(
                CustomShortcutSet(*shortcuts),
                editor.contentComponent,
                this
            )
        }
    }

    private fun resolvePreferredKeyStroke(actionId: String, keyCode: Int): KeyStroke {
        val keymap = KeymapManager.getInstance().activeKeymap
        val shortcuts = keymap.getShortcuts(actionId)
        val macKs = KeyStroke.getKeyStroke(keyCode, InputEvent.META_DOWN_MASK)
        val winKs = KeyStroke.getKeyStroke(keyCode, InputEvent.CTRL_DOWN_MASK)
        val fromKeymap =
            shortcuts.firstOrNull { (it as? KeyboardShortcut)?.firstKeyStroke == macKs }
                ?: shortcuts.firstOrNull { (it as? KeyboardShortcut)?.firstKeyStroke == winKs }
        val ks = (fromKeymap as? KeyboardShortcut)?.firstKeyStroke
        if (ks != null) return ks
        return if (SystemInfo.isMac) macKs else winKs
    }
}