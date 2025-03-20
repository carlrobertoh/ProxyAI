package ee.carlrobert.codegpt.ui.textarea.header.tag

import com.intellij.icons.AllIcons
import com.intellij.icons.AllIcons.Actions.Close
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.ui.textarea.PromptTextField
import ee.carlrobert.codegpt.ui.textarea.header.PaintUtil
import java.awt.*
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JToggleButton

abstract class TagPanel(
    var tagDetails: TagDetails,
    private val tagManager: TagManager,
    private val shouldPreventDeselection: Boolean = true,
) : JToggleButton() {

    private val label = TagLabel(tagDetails.name, tagDetails.icon, tagDetails.selected)
    private val closeButton = CloseButton {
        isVisible = isSelected
        onClose()
    }
    private var isRevertingSelection = false

    init {
        setupUI()
    }

    abstract fun onSelect(tagDetails: TagDetails)

    abstract fun onClose()

    fun update(text: String, icon: Icon? = null) {
        closeButton.isVisible = isSelected
        label.update(text, icon, isSelected)
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val closeButtonWidth = if (closeButton.isVisible) closeButton.preferredSize.width else 0
        return Dimension(
            label.preferredSize.width + closeButtonWidth + insets.left + insets.right,
            20
        )
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        PaintUtil.drawRoundedBackground(g, this, isSelected)
    }

    private fun setupUI() {
        isOpaque = false
        layout = GridBagLayout()
        border = JBUI.Borders.empty(2, 6)
        cursor = Cursor(Cursor.HAND_CURSOR)
        isSelected = tagDetails.selected
        closeButton.isVisible = isSelected

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.CENTER
            fill = GridBagConstraints.NONE
        }

        add(label, gbc)
        gbc.gridx = 1
        add(closeButton, gbc)

        addActionListener {
            if (isRevertingSelection) return@addActionListener

            onSelect(tagDetails)

            if (!isSelected && shouldPreventDeselection) {
                isRevertingSelection = true
                isSelected = true
                isRevertingSelection = false
            }

            closeButton.isVisible = isSelected
            tagDetails.selected = isSelected
            tagManager.notifySelectionChanged(tagDetails)
            label.update(isSelected)
        }

        revalidate()
        repaint()
    }

    private class TagLabel(
        name: String,
        icon: Icon? = null,
        selected: Boolean = true
    ) : JBLabel(name) {

        init {
            update(name, icon, selected)
        }

        fun update(selected: Boolean) {
            update(text, icon, selected)
        }

        fun update(name: String, icon: Icon? = null, selected: Boolean = true) {
            text = name
            font = JBUI.Fonts.miniFont()
            foreground = if (selected) {
                service<EditorColorsManager>().globalScheme.defaultForeground
            } else {
                JBUI.CurrentTheme.Label.disabledForeground(false)
            }
            icon?.let {
                this.icon = IconUtil.scale(it, null, 0.65f)
            }
        }

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return Dimension(size.width, 16)
        }
    }

    private class CloseButton(onClose: () -> Unit) : JButton(Close) {
        init {
            addActionListener {
                onClose()
            }

            preferredSize = Dimension(Close.iconWidth, Close.iconHeight)
            border = JBUI.Borders.emptyLeft(4)
            isContentAreaFilled = false
            toolTipText = "Remove"
            rolloverIcon = AllIcons.Actions.CloseHovered
        }
    }
}

class SelectionTagPanel(
    tagDetails: EditorSelectionTagDetails,
    tagManager: TagManager,
    private val promptTextField: PromptTextField,
) : TagPanel(tagDetails, tagManager, true) {

    init {
        cursor = Cursor(Cursor.DEFAULT_CURSOR)
    }

    init {
        isVisible = tagDetails !is EmptyTagDetails
    }

    override fun onSelect(tagDetails: TagDetails) {
        promptTextField.requestFocus()
    }

    override fun onClose() {
        (tagDetails as? EditorSelectionTagDetails)?.selectionModel?.removeSelection()
    }
}