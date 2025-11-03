package ee.carlrobert.codegpt.predictions

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.progress.EmptyProgressIndicator

object NextEditDiffComputer {
    fun computeHunks(base: String, proposed: String, docLength: Int): List<Hunk> {
        val lineFragments = ComparisonManager.getInstance()
            .compareLines(
                base,
                proposed,
                ComparisonPolicy.DEFAULT,
                EmptyProgressIndicator()
            )
        if (lineFragments.isEmpty()) return emptyList()

        val baseLineOffsets = computeLineStartOffsets(base)
        val proposedLineOffsets = computeLineStartOffsets(proposed)
        val list = mutableListOf<Hunk>()
        for (frag in lineFragments) {
            val baseStart = if (frag.startLine1 < baseLineOffsets.size) baseLineOffsets[frag.startLine1] else base.length
            val baseEnd = if (frag.endLine1 < baseLineOffsets.size) baseLineOffsets[frag.endLine1] else base.length
            val newStart = if (frag.startLine2 < proposedLineOffsets.size) proposedLineOffsets[frag.startLine2] else proposed.length
            val newEnd = if (frag.endLine2 < proposedLineOffsets.size) proposedLineOffsets[frag.endLine2] else proposed.length

            val oldSlice = safeSlice(base, baseStart, baseEnd)
            val newSlice = safeSlice(proposed, newStart, newEnd)
            val oldTrimNl = oldSlice.trimEnd('\n', '\r')
            val newTrimNl = newSlice.trimEnd('\n', '\r')
            if (oldSlice == newSlice || oldTrimNl == newTrimNl) continue

            val start = baseStart.coerceIn(0, docLength)
            val end = baseEnd.coerceIn(start, docLength)
            val delOnly = newTrimNl.isEmpty() && oldTrimNl.isNotEmpty()
            if (delOnly) continue
            list.add(Hunk(start, end, oldSlice, newSlice, delOnly))
        }
        return list
    }

    fun computeJumpAnchor(h: Hunk, docLength: Int): Int {
        val oldEff = h.oldSlice.trimEnd('\n', '\r')
        val newEff = h.newSlice.trimEnd('\n', '\r')
        val singleLineOld = !oldEff.contains('\n')
        val singleLineNew = !newEff.contains('\n')
        if (singleLineOld && singleLineNew) {
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
                val first = frags.first()
                val s1 = first.startOffset1
                return (h.start + s1).coerceIn(0, docLength)
            }
            val lcp = commonPrefixLength(oldEff, newEff)
            return (h.start + lcp).coerceIn(0, docLength)
        }
        return h.start
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

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }
}
