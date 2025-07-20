package ee.carlrobert.codegpt.toolwindow.history

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.conversations.Conversation
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max

class ChatHistoryItemPanel(
    private val conversation: Conversation,
    private val onClicked: () -> Unit,
    private val onDoubleClicked: () -> Unit,
    private val onDeleteClicked: () -> Unit,
    private var isSelected: Boolean = false
) : BorderLayoutPanel() {

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, h:mm a")
        private const val TITLE_MAX_LENGTH = 60
        private const val PREVIEW_MAX_LENGTH = 100
        private const val MIN_PANEL_HEIGHT = 80
        private const val BUTTON_SIZE = 20
    }

    private val deleteButton: JButton = createDeleteButton()
    private var isHovered = false

    init {
        setupUI()
        setupMouseListeners()
    }

    fun setSelected(selected: Boolean) {
        if (isSelected != selected) {
            isSelected = selected
            setupBackground()
            repaint()
        }
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width, max(size.height, MIN_PANEL_HEIGHT))
    }

    private fun setupUI() {
        cursor = Cursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        setupBackground()
        addToCenter(createMainPanel())
    }

    private fun setupMouseListeners() {
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                when (e.clickCount) {
                    1 -> onClicked()
                    2 -> onDoubleClicked()
                }
            }

            override fun mouseEntered(e: MouseEvent) {
                isHovered = true
                setupBackground()
                repaint()
                deleteButton.icon = AllIcons.Actions.Close
                deleteButton.rolloverIcon = AllIcons.Actions.CloseHovered
            }

            override fun mouseExited(e: MouseEvent) {
                val point =
                    SwingUtilities.convertPoint(e.component, e.point, this@ChatHistoryItemPanel)
                if (!contains(point)) {
                    isHovered = false
                    setupBackground()
                    repaint()
                    deleteButton.icon = null
                    deleteButton.rolloverIcon = null
                }
            }
        })
    }

    private fun setupBackground() {
        background = when {
            isSelected -> UIUtil.getListSelectionBackground(true)
            isHovered -> UIUtil.getListBackground().darker()
            else -> UIUtil.getListBackground()
        }
    }

    private fun createDeleteButton(): JButton {
        return JButton().apply {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            toolTipText = CodeGPTBundle.get("conversation.deleteButton.tooltip")
            preferredSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
            minimumSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
            maximumSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
            icon = null

            addActionListener {
                onDeleteClicked()
            }

            isVisible = true

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isHovered = true
                    setupBackground()
                    this@ChatHistoryItemPanel.repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    val parentPoint =
                        SwingUtilities.convertPoint(e.component, e.point, this@ChatHistoryItemPanel)
                    if (!this@ChatHistoryItemPanel.contains(parentPoint)) {
                        isHovered = false
                        setupBackground()
                        this@ChatHistoryItemPanel.repaint()
                        icon = null
                        rolloverIcon = null
                    }
                }
            })
        }
    }

    private fun createMainPanel(): JPanel {
        return panel {
            row {
                cell(createTitlePanel())
                    .align(Align.FILL)
            }.resizableRow()

            val previewText = getPreviewText()
            if (previewText.isNotEmpty()) {
                row {
                    cell(createPreviewLabel(previewText))
                        .align(Align.FILL)
                }.topGap(TopGap.NONE)
            }

            row {
                cell(createMetadataPanel())
                    .align(Align.FILL)
            }.topGap(TopGap.NONE)
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
        }
    }

    private fun createTitlePanel(): JPanel {
        return panel {
            row {
                cell(createTitleLabel())
                    .align(AlignX.FILL)
                    .resizableColumn()
                cell(deleteButton)
                    .align(AlignY.CENTER)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 2, 0)
        }
    }

    private fun createPreviewLabel(previewText: String): JBLabel {
        return JBLabel(previewText).apply {
            font = JBFont.regular().deriveFont(12f)
            foreground = getPreviewColor()
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height * 2)
        }
    }

    private fun createMetadataPanel(): JPanel {
        return panel {
            row {
                label(formatDate())
                    .applyToComponent {
                        font = JBFont.regular().deriveFont(11f)
                        foreground = getMetadataColor()
                    }
                    .align(AlignX.LEFT)
                    .resizableColumn()

                val messageCount = conversation.messages.size
                if (messageCount > 0) {
                    val text = if (messageCount == 1) {
                        CodeGPTBundle.get("conversation.messageCount.singular", messageCount)
                    } else {
                        CodeGPTBundle.get("conversation.messageCount.plural", messageCount)
                    }
                    label(text)
                        .applyToComponent {
                            font = JBFont.regular().deriveFont(11f)
                            foreground = getMetadataColor()
                        }
                        .align(AlignX.RIGHT)
                }
            }
        }.apply {
            isOpaque = false
        }
    }

    private fun createTitleLabel(): JBLabel {
        return JBLabel(getConversationDisplayTitle()).apply {
            font = JBFont.label().deriveFont(14f).asBold()
            foreground = UIUtil.getLabelForeground()
        }
    }

    private fun getPreviewColor(): Color {
        return JBColor(Color(128, 128, 128), Color(169, 169, 169))
    }

    private fun getMetadataColor(): Color {
        return JBColor(Color(150, 150, 150), Color(130, 130, 130))
    }

    private fun getConversationDisplayTitle(): String {
        val title = conversation.title?.takeIf { it.isNotBlank() } ?: getFirstPrompt()
        return if (title.length > TITLE_MAX_LENGTH) {
            title.substring(0, TITLE_MAX_LENGTH - 3) + "..."
        } else {
            title
        }
    }

    private fun getFirstPrompt(): String {
        return conversation.messages.firstOrNull()?.prompt?.trim()
            ?: CodeGPTBundle.get("conversation.defaultTitle")
    }

    private fun getPreviewText(): String {
        val lastMessage = conversation.messages.lastOrNull() ?: return ""
        val text = lastMessage.response?.trim() ?: lastMessage.prompt?.trim() ?: ""

        val cleanedText = text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()

        return if (cleanedText.length > PREVIEW_MAX_LENGTH) {
            cleanedText.substring(0, PREVIEW_MAX_LENGTH - 3) + "..."
        } else {
            cleanedText
        }
    }

    private fun formatDate(): String {
        return conversation.updatedOn.format(DATE_FORMATTER)
    }
}