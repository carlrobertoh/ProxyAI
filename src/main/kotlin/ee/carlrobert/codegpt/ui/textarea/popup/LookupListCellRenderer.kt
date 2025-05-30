package ee.carlrobert.codegpt.ui.textarea.popup

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItem
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class LookupListCellRenderer : ListCellRenderer<LookupItem> {
    
    override fun getListCellRendererComponent(
        list: JList<out LookupItem>,
        value: LookupItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
        }

        val component = SimpleColoredComponent().apply {
            icon = value.icon
            append(value.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            isOpaque = false
        }

        panel.add(component, BorderLayout.CENTER)

        if (isSelected) {
            panel.background = list.selectionBackground
            component.foreground = list.selectionForeground
        } else {
            panel.background = list.background
            component.foreground = list.foreground
        }

        panel.isOpaque = true
        return panel
    }
} 