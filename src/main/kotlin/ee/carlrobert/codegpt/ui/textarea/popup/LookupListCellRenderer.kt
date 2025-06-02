package ee.carlrobert.codegpt.ui.textarea.popup

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ee.carlrobert.codegpt.ui.textarea.lookup.LoadingLookupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupGroupItem
import ee.carlrobert.codegpt.ui.textarea.lookup.LookupItem
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*

class LookupListCellRenderer : ListCellRenderer<LookupItem> {

    override fun getListCellRendererComponent(
        list: JList<out LookupItem>,
        value: LookupItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val panel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(list.width, ITEM_HEIGHT)
            border = JBUI.Borders.empty(0, 0, 0, 0)
        }

        val component = SimpleColoredComponent().apply {
            icon = value.icon
            iconTextGap = ICON_TEXT_GAP
            isOpaque = false
            ipad = JBUI.insets(TOP_BOTTOM_MARGIN, LEFT_MARGIN, TOP_BOTTOM_MARGIN, 0)

            when (value) {
                is LoadingLookupItem -> {
                    append(value.displayName, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }

                is LookupGroupItem -> {
                    append(value.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }

                else -> {
                    append(value.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        panel.add(component, BorderLayout.CENTER)

        if (value is LookupGroupItem) {
            val arrowLabel = JLabel().apply {
                icon = AllIcons.Icons.Ide.NextStep
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
                border = JBUI.Borders.empty(0, JBUIScale.scale(4), 0, RIGHT_MARGIN)
                isOpaque = false

                if (isSelected) {
                    foreground = UIUtil.getListSelectionForeground(true)
                } else {
                    foreground = UIUtil.getListForeground()
                }
            }
            panel.add(arrowLabel, BorderLayout.EAST)
        } else {
            val spacer = Box.createHorizontalStrut(RIGHT_MARGIN + JBUIScale.scale(16))
            panel.add(spacer, BorderLayout.EAST)
        }

        if (isSelected) {
            panel.background = UIUtil.getListSelectionBackground(true)
            component.foreground = UIUtil.getListSelectionForeground(true)
        } else {
            panel.background = UIUtil.getListBackground()
            component.foreground = when (value) {
                is LoadingLookupItem -> JBColor.GRAY
                else -> UIUtil.getListForeground()
            }
        }

        panel.isOpaque = true
        return panel
    }

    private companion object {
        val ITEM_HEIGHT = JBUIScale.scale(20)
        val ICON_TEXT_GAP = JBUIScale.scale(4)
        val LEFT_MARGIN = JBUIScale.scale(8)
        val RIGHT_MARGIN = JBUIScale.scale(8)
        val TOP_BOTTOM_MARGIN = JBUIScale.scale(2)
    }
} 