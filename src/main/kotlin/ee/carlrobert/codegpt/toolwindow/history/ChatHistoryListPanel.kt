package ee.carlrobert.codegpt.toolwindow.history

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.conversations.Conversation
import java.awt.BorderLayout
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.*
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

class ChatHistoryListPanel : BorderLayoutPanel() {

    private val itemsPanel = JPanel()
    private var scrollPane = JBScrollPane(itemsPanel)
    private var conversations = listOf<Conversation>()
    private var selectedConversation: Conversation? = null
    private var onConversationSelected: ((Conversation?) -> Unit)? = null
    private var onConversationDoubleClicked: ((Conversation) -> Unit)? = null
    private var onConversationDeleted: ((Conversation) -> Unit)? = null
    private val conversationPanels = mutableMapOf<UUID, ChatHistoryItemPanel>()

    init {
        setupItemsPanel()
        setupScrollPane()
        setupKeyboardSupport()
    }

    fun setConversations(newConversations: List<Conversation>) {
        val previousSelection = selectedConversation
        conversations = newConversations
        rebuildItems()

        previousSelection?.let { selected ->
            conversations.find { it.id == selected.id }?.let { setSelectedConversation(it) }
        }
    }

    fun setSelectedConversation(conversation: Conversation?) {
        if (selectedConversation != conversation) {
            selectedConversation?.let { prev ->
                conversationPanels[prev.id]?.setSelected(false)
            }

            selectedConversation = conversation

            conversation?.let { conv ->
                conversationPanels[conv.id]?.setSelected(true)
            }

            onConversationSelected?.invoke(conversation)
        }
    }

    fun setOnConversationSelected(callback: (Conversation?) -> Unit) {
        onConversationSelected = callback
    }

    fun setOnConversationDoubleClicked(callback: (Conversation) -> Unit) {
        onConversationDoubleClicked = callback
    }

    fun setOnConversationDeleted(callback: (Conversation) -> Unit) {
        onConversationDeleted = callback
    }

    private fun setupItemsPanel() {
        itemsPanel.layout = BoxLayout(itemsPanel, BoxLayout.Y_AXIS)
        itemsPanel.background = UIUtil.getListBackground()
    }

    private fun setupScrollPane() {
        scrollPane.apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            border = null
            viewportBorder = null
            background = UIUtil.getListBackground()
        }
        addToCenter(scrollPane)
    }

    private fun setupKeyboardSupport() {
        isFocusable = true
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_UP -> selectPrevious()
                    KeyEvent.VK_DOWN -> selectNext()
                    KeyEvent.VK_ENTER -> selectedConversation?.let {
                        onConversationDoubleClicked?.invoke(
                            it
                        )
                    }

                    KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> {
                        selectedConversation?.let { onConversationDeleted?.invoke(it) }
                    }
                }
            }
        })
    }

    private fun selectPrevious() {
        val currentIndex = selectedConversation?.let { conversations.indexOf(it) } ?: 0
        if (currentIndex > 0) {
            setSelectedConversation(conversations[currentIndex - 1])
            scrollToSelected()
        }
    }

    private fun selectNext() {
        val currentIndex = selectedConversation?.let { conversations.indexOf(it) } ?: -1
        if (currentIndex < conversations.size - 1) {
            setSelectedConversation(conversations[currentIndex + 1])
            scrollToSelected()
        }
    }

    private fun scrollToSelected() {
        selectedConversation?.let { selected ->
            val index = conversations.indexOf(selected)
            if (index >= 0 && index < itemsPanel.componentCount) {
                val component = itemsPanel.getComponent(index)
                scrollPane.viewport.scrollRectToVisible(
                    Rectangle(0, component.y, component.width, component.height)
                )
            }
        }
    }

    private fun rebuildItems() {
        itemsPanel.removeAll()

        if (conversations.isEmpty()) {
            addEmptyState()
        } else {
            addConversationItems()
        }

        itemsPanel.revalidate()
        itemsPanel.repaint()
    }

    private fun addEmptyState() {
        val emptyPanel = panel {
            row {
                label(CodeGPTBundle.get("conversation.emptyState"))
                    .applyToComponent {
                        font = JBFont.regular().deriveFont(14f)
                        foreground = UIUtil.getInactiveTextColor()
                    }
            }
        }.apply {
            border = JBUI.Borders.empty(40)
            isOpaque = false
        }

        itemsPanel.layout = BorderLayout()
        itemsPanel.add(emptyPanel, BorderLayout.CENTER)
    }

    private fun addConversationItems() {
        itemsPanel.layout = BoxLayout(itemsPanel, BoxLayout.Y_AXIS)

        conversationPanels.clear()

        conversations.forEach { conversation ->
            val isCurrentlySelected = selectedConversation?.id == conversation.id
            val itemPanel = ChatHistoryItemPanel(
                conversation = conversation,
                onClicked = {
                    setSelectedConversation(conversation)
                    onConversationDoubleClicked?.invoke(conversation)
                },
                onDoubleClicked = { onConversationDoubleClicked?.invoke(conversation) },
                onDeleteClicked = { onConversationDeleted?.invoke(conversation) },
                isSelected = isCurrentlySelected
            )

            itemPanel.alignmentX = LEFT_ALIGNMENT
            itemPanel.maximumSize =
                java.awt.Dimension(Integer.MAX_VALUE, itemPanel.preferredSize.height)
            itemsPanel.add(itemPanel)
            conversationPanels[conversation.id] = itemPanel
        }
    }
}