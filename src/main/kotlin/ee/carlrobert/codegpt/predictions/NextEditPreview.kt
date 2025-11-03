package ee.carlrobert.codegpt.predictions

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.EmptyProgressIndicator

sealed class PreviewElement {
    data class RangeHighlight(val start: Int, val end: Int) : PreviewElement()
    data class GhostInline(val offset: Int, val text: String) : PreviewElement()
    data class BlockDiff(val anchorOffset: Int, val oldText: String, val newText: String, val additionsOnly: Boolean, val showAbove: Boolean) : PreviewElement()
}

object PreviewElementBuilder {
    fun build(editor: Editor, ch: ClassifiedHunk): List<PreviewElement> {
        val h = ch.hunk
        val elements = mutableListOf<PreviewElement>()

        when (ch.scope) {
            Scope.MULTI_LINE -> {
                if (h.end > h.start) {
                    elements += PreviewElement.RangeHighlight(h.start, h.end)
                }

                val doc = editor.document
                val endPos = h.end.coerceIn(0, doc.textLength)
                val endLine = if (endPos == 0) 0 else doc.getLineNumber((endPos - 1).coerceAtLeast(0))
                val nextLine = (endLine + 1).coerceAtMost(doc.lineCount - 1)
                val hasNextLine = endLine + 1 < doc.lineCount
                val anchorBelowOffset = if (hasNextLine) doc.getLineStartOffset(nextLine) else doc.getLineStartOffset(endLine)
                val showAbove = hasNextLine

                elements += PreviewElement.BlockDiff(anchorBelowOffset, h.oldSlice, h.newSlice, additionsOnly = true, showAbove = showAbove)
                return elements
            }

            Scope.SINGLE_LINE -> {
                when (ch.kind) {
                    HunkKind.DELETION -> {
                        if (h.end > h.start) elements += PreviewElement.RangeHighlight(h.start, h.end)
                        return elements
                    }

                    HunkKind.INSERTION, HunkKind.REPLACEMENT -> {
                        val oldEff = h.oldSlice.trimEnd('\n', '\r')
                        val newEff = h.newSlice.trimEnd('\n', '\r')
                        val frags = try {
                            ComparisonManager.getInstance().compareWords(
                                oldEff,
                                newEff,
                                ComparisonPolicy.DEFAULT,
                                EmptyProgressIndicator()
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }

                        if (frags.isNotEmpty()) {
                            val docLen = editor.document.textLength
                            for (f in frags) {
                                val s1 = f.startOffset1
                                val e1 = f.endOffset1
                                val s2 = f.startOffset2
                                val e2 = f.endOffset2
                                val oldLen = e1 - s1
                                val newLen = e2 - s2

                                if (oldLen > 0) {
                                    val absStart = (h.start + s1).coerceIn(0, docLen)
                                    val absEnd = (h.start + e1).coerceIn(absStart, docLen)
                                    elements += PreviewElement.RangeHighlight(absStart, absEnd)
                                }

                                if (newLen > 0) {
                                    val piece = try { newEff.substring(s2, e2) } catch (_: Exception) { "" }
                                    if (piece.isNotBlank() && !piece.contains('\n')) {
                                        val abs = (h.start + s1).coerceIn(0, docLen)
                                        elements += PreviewElement.GhostInline(abs, piece)
                                    }
                                }
                            }
                            return elements
                        }

                        // Fallbacks mirroring original logic
                        if (h.end == h.start) {
                            val ghost = h.newSlice.replace("\r", "").lineSequence().firstOrNull { it.isNotBlank() }?.trimEnd('\n').orEmpty()
                            if (ghost.isNotEmpty()) elements += PreviewElement.GhostInline(h.start, ghost)
                            return elements
                        } else {
                            if (!h.deletionOnly) {
                                if (h.end > h.start) elements += PreviewElement.RangeHighlight(h.start, h.end)
                                val ghost = h.newSlice.replace("\r", "").lineSequence().firstOrNull { it.isNotBlank() }?.trimEnd('\n').orEmpty()
                                if (ghost.isNotEmpty()) elements += PreviewElement.GhostInline(h.start, ghost)
                            } else {
                                if (h.end > h.start) elements += PreviewElement.RangeHighlight(h.start, h.end)
                            }
                            return elements
                        }
                    }
                }
            }
        }
    }
}
