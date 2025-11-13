package ee.carlrobert.codegpt.predictions

import com.intellij.codeInsight.inline.completion.session.InlineCompletionContext
import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import com.intellij.util.application
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.actions.editor.EditorComponentInlaysManager
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.service.NextEditResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Color

class NextEditSuggestionNavigator(private val editor: Editor) : Disposable {
    private val logger = thisLogger()

    private var jumpInlay: Inlay<NextEditTagInlineRenderer>? = null
    private var previewInlay: Disposable? = null
    private val previewHighlighters = mutableListOf<RangeHighlighter>()
    private val previewGhostInlays = mutableListOf<Inlay<*>>()

    private val hunks = mutableListOf<Hunk>()
    private val changeOffsets = mutableListOf<Int>()
    private var currentIndex: Int = -1
    private var previewIndex: Int = -1
    private var listenerAdded: Boolean = false
    private var keyDispatcherInstalled = false
    private var applying: Boolean = false
    private var proposedRevision: String = ""
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (!applying) dispose()
        }
    }

    @RequiresEdt
    fun showFor(computed: List<Hunk>, nextRevision: String) {
        application.executeOnPooledThread {
            runInEdt {
                hunks.clear()
                hunks.addAll(computed)
                proposedRevision = nextRevision
                changeOffsets.clear()
                changeOffsets.addAll(hunks.map { it.start }.sorted())

                val caret = editor.caretModel.offset
                previewIndex = findHunkAtOrNearCaret(caret)

                val displayImmediately = previewIndex in hunks.indices
                if (displayImmediately) {
                    renderPreviewInlay()
                    val basePosForNext = hunks[previewIndex].start
                    val strictNext = nextIndexStrictAfter(basePosForNext)
                    currentIndex = strictNext
                    disposeJump()
                } else {
                    val strictNext = nextIndexStrictAfter(caret)
                    currentIndex = strictNext
                    renderJumpInlay()
                }
                installKeyDispatcher()
                editor.document.addDocumentListener(documentListener, this)
                listenerAdded = true
            }
        }
    }

    fun isVisible(): Boolean = changeOffsets.isNotEmpty()

    fun hasPreview(): Boolean = previewIndex in hunks.indices

    @RequiresEdt
    fun jumpToNext() {
        if (changeOffsets.isEmpty()) return
        if (currentIndex !in changeOffsets.indices) currentIndex = 0
        val baseOffset = changeOffsets[currentIndex]
        val h = hunks.getOrNull(currentIndex)
        val jumpOffset =
            h?.let { NextEditDiffComputer.computeJumpAnchor(it, editor.document.textLength) }
                ?: baseOffset
        editor.caretModel.moveToOffset(jumpOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        previewIndex = currentIndex
        currentIndex = nextIndexAfter(baseOffset)
        renderPreviewInlay()
        disposeJump()
    }

    @RequiresEdt
    fun acceptCurrent() {
        if (previewIndex !in hunks.indices) return
        val h = hunks[previewIndex]
        applying = true
        try {
            val doc = editor.document
            val start = h.start.coerceIn(0, doc.textLength)
            val end = h.end.coerceIn(start, doc.textLength)
            WriteCommandAction.runWriteCommandAction(editor.project) {
                doc.replaceString(start, end, h.newSlice)
            }
            val nonNlLen = try {
                h.newSlice.trimEnd('\n', '\r').length
            } catch (_: Exception) {
                h.newSlice.length
            }
            val appliedEnd = (start + nonNlLen).coerceIn(start, editor.document.textLength)
            editor.caretModel.moveToOffset(appliedEnd)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            val response = CodeGPTKeys.REMAINING_NEXT_EDITS.get(editor)
            val cursor = appliedEnd
            if (response != null) {
                val oldHunk = h.oldSlice
                val newHunk = h.newSlice
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    editor.project?.service<GrpcClientService>()
                        ?.acceptEdit(response.id, oldHunk, newHunk, cursor)
                }
            }
        } finally {
            applying = false
        }

        disposePreview()
        val removedOffset = h.start
        val recomputed =
            NextEditDiffComputer.computeHunks(
                editor.document.text,
                proposedRevision,
                editor.document.textLength
            )
        hunks.clear()
        hunks.addAll(recomputed)
        changeOffsets.clear()
        changeOffsets.addAll(hunks.map { it.start }.sorted())

        if (changeOffsets.isEmpty()) {
            dispose()
            return
        }
        val basePos = removedOffset
        currentIndex = nextIndexAfter(basePos)
        previewIndex = -1
        renderJumpInlay()
    }

    private fun installKeyDispatcher() {
        if (keyDispatcherInstalled) return
        NextEditKeyEventDispatcher(
            editor = editor,
            isActive = { hasPreview() || isVisible() },
            onEscape = {
                try {
                    dispose()
                } finally {
                    editor.putUserData(NAVIGATOR_KEY, null)
                    editor.putUserData(CodeGPTKeys.REMAINING_NEXT_EDITS, null)
                }
            },
            onTab = {
                if (hasPreview()) acceptCurrent() else jumpToNext()
            },
            onDisposeForEdit = {
                dispose()
                editor.putUserData(NAVIGATOR_KEY, null)
                editor.putUserData(CodeGPTKeys.REMAINING_NEXT_EDITS, null)
            }
        ).register(this)
        keyDispatcherInstalled = true
    }

    override fun dispose() {
        disposeJump()
        disposePreview()
        changeOffsets.clear()
        hunks.clear()
        if (listenerAdded) {
            try {
                editor.document.removeDocumentListener(documentListener)
            } catch (e: Exception) {
                logger.warn("Failed to remove document listener", e)
            }
            listenerAdded = false
        }
        try {
            editor.putUserData(NAVIGATOR_KEY, null)
            editor.putUserData(CodeGPTKeys.REMAINING_NEXT_EDITS, null)
        } catch (_: Exception) {
        }
    }

    private fun renderJumpInlay() {
        disposeJump()
        if (changeOffsets.isEmpty()) return

        if (previewIndex in hunks.indices) return

        if (hunks.size == 1 && previewIndex !in hunks.indices) {
            val caret = editor.caretModel.offset
            val only = hunks.first()
            val doc = editor.document
            val caretLine = doc.getLineNumber(caret)
            val hunkLine = doc.getLineNumber(only.start)
            if (caretLine == hunkLine) {
                val newEff = only.newSlice.trimEnd('\n', '\r')
                val firstNonWs =
                    newEff.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) 0 else it }
                val effectiveNewStart = (only.start + firstNonWs).coerceIn(0, doc.textLength)
                if (caret <= effectiveNewStart) return
            }
        }

        if (currentIndex !in changeOffsets.indices) currentIndex = 0
        val defaultTarget = changeOffsets[currentIndex]
        val h = hunks.getOrNull(currentIndex)
        val anchorOffset = if (h != null) {
            NextEditDiffComputer.computeJumpAnchor(h, editor.document.textLength)
        } else defaultTarget

        val label = CodeGPTBundle.get("nextEdit.jumpToNextLabel")
        val inlay =
            editor.inlayModel.addInlineElement(anchorOffset, NextEditTagInlineRenderer(label))
        jumpInlay = inlay
        ensureOffsetVisible(anchorOffset.coerceIn(0, editor.document.textLength))
    }

    @RequiresEdt
    private fun renderPreviewInlay() {
        disposePreview()
        if (previewIndex !in hunks.indices) return
        val h = hunks[previewIndex]
        val classified = NextEditClassifier.classify(h)
        val elements = PreviewElementBuilder.build(editor, classified)

        val attrs = TextAttributes().apply { backgroundColor = Color(220, 60, 60, 40) }
        val docLen = editor.document.textLength
        elements.forEach { el ->
            when (el) {
                is PreviewElement.RangeHighlight -> {
                    val safeStart = el.start.coerceIn(0, docLen)
                    val safeEnd = el.end.coerceIn(safeStart, docLen)
                    val hl = editor.markupModel.addRangeHighlighter(
                        safeStart,
                        safeEnd,
                        HighlighterLayer.SELECTION - 1,
                        attrs,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                    previewHighlighters.add(hl)
                }

                is PreviewElement.GhostInline -> {
                    val safeOffset = el.offset.coerceIn(0, docLen)
                    val inlay = editor.inlayModel.addInlineElement(
                        safeOffset,
                        NextEditGhostInlineRenderer(el.text)
                    )
                    inlay?.let { previewGhostInlays.add(it) }
                }

                is PreviewElement.BlockDiff -> {
                    val mgr = EditorComponentInlaysManager.from(editor)
                    val component = NextEditDiffBlockComponent(
                        editor,
                        el.oldText,
                        el.newText,
                        additionsOnly = el.additionsOnly
                    )
                    val safeAnchor = el.anchorOffset.coerceIn(0, docLen)
                    previewInlay = mgr.insert(safeAnchor, component, showAbove = el.showAbove)
                }
            }
        }

        val doc = editor.document
        val safeStart = h.start.coerceIn(0, (docLen - 1).coerceAtLeast(0))
        val line = doc.getLineNumber(safeStart)
        val anchor = doc.getLineEndOffset(line).coerceIn(0, docLen)
        val label = CodeGPTBundle.get("nextEdit.acceptLabel")
        editor.inlayModel.addInlineElement(anchor, NextEditTagInlineRenderer(label))
            ?.let { previewGhostInlays.add(it) }

        val startLineOffset =
            editor.document.getLineStartOffset(editor.document.getLineNumber(h.start))
        ensureOffsetVisible(startLineOffset)
    }

    private fun disposeJump() {
        jumpInlay?.dispose()
        jumpInlay = null
    }

    private fun disposePreview() {
        previewInlay?.dispose()
        previewInlay = null
        if (previewGhostInlays.isNotEmpty()) {
            previewGhostInlays.forEach { it.dispose() }
            previewGhostInlays.clear()
        }
        if (previewHighlighters.isNotEmpty()) {
            previewHighlighters.forEach { h ->
                try {
                    if (h.isValid) h.dispose()
                } catch (_: Exception) {
                }
            }
            previewHighlighters.clear()
        }
    }

    private fun ensureOffsetVisible(offset: Int) {
        try {
            val area = editor.scrollingModel.visibleArea
            val pt = editor.offsetToXY(offset)
            val bottom = area.y + area.height
            val top = area.y
            val right = area.x + area.width
            val left = area.x
            val margin = JBUI.scale(24)
            val outVert = pt.y > bottom - margin || pt.y < top + margin
            val outHorz = pt.x > right - margin || pt.x < left + margin
            if (outVert || outHorz) {
                val logicalPosition = editor.offsetToLogicalPosition(offset)
                editor.scrollingModel.scrollTo(logicalPosition, ScrollType.CENTER)
            }
        } catch (_: Exception) {
        }
    }

    private fun nextIndexAfter(pos: Int): Int {
        val idx = changeOffsets.indexOfFirst { it > pos }
        return if (idx != -1) idx else 0
    }

    private fun nextIndexStrictAfter(pos: Int): Int {
        if (changeOffsets.isEmpty()) return -1
        val idx = changeOffsets.indexOfFirst { it > pos }
        return if (idx != -1) idx else 0
    }

    private fun findHunkAtOrNearCaret(caret: Int): Int {
        val insideIdx = hunks.indexOfFirst { h ->
            val effectiveEnd = if (h.end > h.start) h.end else h.start
            caret in h.start..effectiveEnd
        }
        if (insideIdx != -1) {
            return insideIdx
        }
        val exactIdx = hunks.indexOfFirst { h -> h.start == caret }
        if (exactIdx != -1) {
            return exactIdx
        }

        return -1
    }

    companion object {
        val NAVIGATOR_KEY: Key<NextEditSuggestionNavigator> =
            Key.create("codegpt.editorPredictionNavigator")

        @RequiresEdt
        fun display(editor: Editor, nextEditResponse: NextEditResponse) {
            val nextRevision = nextEditResponse.nextRevision
            if (editor.virtualFile == null || editor.isViewer || nextRevision.isEmpty()) return

            val oldText = nextEditResponse.oldRevision
            val hunks =
                NextEditDiffComputer.computeHunks(oldText, nextRevision, editor.document.textLength)
            if (hunks.isEmpty()) return

            editor.getUserData(NAVIGATOR_KEY)?.dispose()
            editor.putUserData(CodeGPTKeys.REMAINING_EDITOR_COMPLETION, null)
            InlineCompletionSession.getOrNull(editor)?.let {
                InlineCompletionContext.getOrNull(editor)?.clear()
            }

            val navigator = NextEditSuggestionNavigator(editor)
            editor.putUserData(NAVIGATOR_KEY, navigator)
            EditorUtil.disposeWithEditor(editor, navigator)
            navigator.showFor(hunks, nextRevision)
            editor.putUserData(CodeGPTKeys.REMAINING_NEXT_EDITS, nextEditResponse)
        }
    }
}
