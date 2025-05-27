package ee.carlrobert.codegpt.toolwindow.chat.editor.diff

import com.intellij.diff.tools.fragmented.UnifiedDiffChange
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JPanel

object DiffColors {
    val INSERTED = Color(0x388E3C)
    val DELETED = Color(0xD32F2F)
    val MODIFIED = Color(0xFBC02D)
}

class DiffStatsComponent {
    companion object {
        fun createStatsPanel(changes: List<UnifiedDiffChange>): JPanel {
            val (inserted, deleted, modified) = DiffUtil.calculateDiffStats(changes)
            
            return JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                if (inserted > 0) add(JBLabel("+$inserted").apply {
                    foreground = DiffColors.INSERTED
                })
                if (deleted > 0) add(JBLabel("-$deleted").apply {
                    foreground = DiffColors.DELETED
                })
                if (modified > 0) add(JBLabel("~$modified").apply {
                    foreground = DiffColors.MODIFIED
                })
            }
        }
        
        fun updateStatsComponent(component: SimpleColoredComponent, changes: List<UnifiedDiffChange>) {
            val (inserted, deleted, modified) = DiffUtil.calculateDiffStats(changes)
            
            component.clear()
            val stats = buildList {
                if (inserted > 0) add("+$inserted" to DiffColors.INSERTED)
                if (deleted > 0) add("-$deleted" to DiffColors.DELETED)
                if (modified > 0) add("~$modified" to DiffColors.MODIFIED)
            }
            
            stats.forEachIndexed { idx, (text, color) ->
                component.append(
                    text,
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)
                )
                if (idx < stats.lastIndex) component.append(" ")
            }
        }
    }
}
