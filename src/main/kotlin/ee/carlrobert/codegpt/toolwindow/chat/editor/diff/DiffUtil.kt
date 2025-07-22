package ee.carlrobert.codegpt.toolwindow.chat.editor.diff

import com.intellij.diff.tools.fragmented.UnifiedDiffChange

object DiffUtil {

    fun calculateDiffStats(changes: List<UnifiedDiffChange>): Triple<Int, Int, Int> =
        changes.fold(Triple(0, 0, 0)) { (ins, del, mod), change ->
            val deletedLines = change.lineFragment.endLine1 - change.lineFragment.startLine1
            val insertedLines = change.lineFragment.endLine2 - change.lineFragment.startLine2
            val minLines = minOf(deletedLines, insertedLines)
            val newMod = if (deletedLines > 0 && insertedLines > 0) mod + minLines else mod
            val newDel = del + (deletedLines - minLines)
            val newIns = ins + (insertedLines - minLines)
            Triple(newIns, newDel, newMod)
        }
}