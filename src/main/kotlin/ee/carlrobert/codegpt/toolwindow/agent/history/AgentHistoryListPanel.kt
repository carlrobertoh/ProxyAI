package ee.carlrobert.codegpt.toolwindow.agent.history

import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.agent.history.AgentHistoryThreadSummary
import ee.carlrobert.codegpt.agent.history.CheckpointRef
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.DocumentEvent

class AgentHistoryListPanel(
    private val defaultLimit: Int? = null
) : BorderLayoutPanel() {

    companion object {
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
        private const val MIN_VISIBLE_ROWS = 2
    }

    var onOpen: ((AgentHistoryThreadSummary) -> Unit)? = null
    var onLoadPage: ((query: String, offset: Int, limit: Int, onResult: (List<AgentHistoryThreadSummary>, Boolean, Int) -> Unit) -> Unit)? =
        null

    private val searchField = SearchTextField()
    private val statusLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(0, 8, 4, 8)
    }
    private val listModel = DefaultListModel<AgentHistoryThreadSummary>()
    private val historyList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = AgentHistoryCellRenderer()
        fixedCellHeight = 54
        visibleRowCount = defaultLimit ?: 5
        border = null
    }
    private val listScrollPane = JBScrollPane(historyList).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        border = null
        viewport.background = UIUtil.getListBackground()
    }
    private val emptyPanel = BorderLayoutPanel().apply {
        border = JBUI.Borders.empty(12, 8)
        addToCenter(
            JBLabel("No conversations found", SwingConstants.LEFT).apply {
                foreground = UIUtil.getContextHelpForeground()
            }
        )
    }

    private val pageSize = 50
    private var hasMore = false
    private var isLoading = false
    private var resetRequested = false
    private var currentQuery = ""
    private var currentTotal = 0
    private var requestId = 0

    init {
        setupUI()
        setupListeners()
        updateStatus()
    }

    fun reload() {
        requestPage(reset = true)
    }

    private fun setupUI() {
        searchField.textEditor.emptyText.text = "Search checkpoint conversations"
        searchField.border = JBUI.Borders.empty(4, 0, 2, 0)
        historyList.border = JBUI.Borders.emptyTop(4)

        val topPanel = BorderLayoutPanel()
            .addToTop(searchField)
            .addToBottom(statusLabel)
        addToTop(topPanel)
        addToCenter(listScrollPane)
    }

    private fun setupListeners() {
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val nextQuery = searchField.text.trim()
                if (nextQuery == currentQuery && !resetRequested) {
                    return
                }
                currentQuery = nextQuery
                requestPage(reset = true)
            }
        })

        listScrollPane.verticalScrollBar.addAdjustmentListener {
            if (!it.valueIsAdjusting && shouldLoadNextPage()) {
                requestPage(reset = false)
            }
        }

        historyList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                    openAt(e)
                }
            }

            override fun mouseExited(e: MouseEvent) {
                historyList.cursor = Cursor.getDefaultCursor()
            }
        })

        historyList.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val hasItem = indexAt(e) >= 0
                historyList.cursor = if (hasItem) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }
        })

        historyList.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "openThread")
        historyList.actionMap.put("openThread", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                openSelected()
            }
        })
    }

    private fun requestPage(reset: Boolean) {
        val loader = onLoadPage ?: return
        if (isLoading) {
            if (reset) {
                resetRequested = true
            }
            return
        }
        if (!reset && !hasMore) {
            return
        }

        val previousSelection = historyList.selectedValue?.latest
        val querySnapshot = currentQuery
        val offset = if (reset) 0 else listModel.size
        val requestSnapshot = ++requestId

        if (reset) {
            listModel.clear()
            hasMore = false
            currentTotal = 0
            updateListViewportSize(0)
            updateCenterView(isEmpty = false)
        }

        isLoading = true
        updateStatus()

        loader(querySnapshot, offset, pageSize) { pageItems, pageHasMore, pageTotal ->
            if (requestSnapshot != requestId) {
                return@loader
            }

            if (querySnapshot != currentQuery) {
                isLoading = false
                if (resetRequested) {
                    resetRequested = false
                }
                requestPage(reset = true)
                return@loader
            }

            if (reset) {
                listModel.clear()
            }

            pageItems.forEach(listModel::addElement)
            hasMore = pageHasMore
            currentTotal = pageTotal
            isLoading = false

            restoreSelection(previousSelection)
            updateListViewportSize(listModel.size)
            updateCenterView(isEmpty = listModel.isEmpty)
            updateStatus()

            if (resetRequested) {
                resetRequested = false
                requestPage(reset = true)
                return@loader
            }

            if (shouldLoadNextPage()) {
                requestPage(reset = false)
            }
        }
    }

    private fun restoreSelection(selected: CheckpointRef?) {
        if (listModel.isEmpty) {
            return
        }

        if (selected != null) {
            val idx = (0 until listModel.size()).firstOrNull {
                val item = listModel.getElementAt(it)
                item.latest.agentId == selected.agentId && item.latest.checkpointId == selected.checkpointId
            }
            if (idx != null) {
                historyList.selectedIndex = idx
                historyList.ensureIndexIsVisible(idx)
                return
            }
        }

        historyList.selectedIndex = 0
    }

    private fun shouldLoadNextPage(): Boolean {
        if (!hasMore || isLoading) {
            return false
        }
        val scrollbar = listScrollPane.verticalScrollBar
        val threshold = historyList.fixedCellHeight.takeIf { it > 0 } ?: 54
        return scrollbar.value + scrollbar.visibleAmount >= scrollbar.maximum - threshold
    }

    private fun updateListViewportSize(itemCount: Int) {
        val maxRows = defaultLimit ?: 5
        val visibleRows = itemCount.coerceIn(MIN_VISIBLE_ROWS, maxRows)
        val rowHeight = historyList.fixedCellHeight.takeIf { it > 0 } ?: 54
        val height = rowHeight * visibleRows
        listScrollPane.minimumSize = Dimension(0, rowHeight * MIN_VISIBLE_ROWS)
        listScrollPane.preferredSize = Dimension(0, height)
    }

    private fun updateStatus() {
        val loaded = listModel.size
        val queryActive = currentQuery.isNotBlank()
        statusLabel.text = when {
            isLoading && loaded == 0 -> "Loading..."
            currentTotal == 0 && queryActive -> "No results"
            currentTotal == 0 -> "No threads"
            isLoading -> "Showing $loaded of $currentTotal..."
            else -> "Showing $loaded of $currentTotal"
        }
    }

    private fun updateCenterView(isEmpty: Boolean) {
        val current = if (componentCount > 1) getComponent(1) else null
        val expected = if (isEmpty) emptyPanel else listScrollPane
        if (current === expected) return
        if (current != null) remove(current)
        addToCenter(expected)
        revalidate()
        repaint()
    }

    private fun openSelected() {
        historyList.selectedValue?.let { onOpen?.invoke(it) }
    }

    private fun openAt(e: MouseEvent) {
        val index = indexAt(e)
        if (index < 0) {
            return
        }
        historyList.selectedIndex = index
        openSelected()
    }

    private fun indexAt(e: MouseEvent): Int {
        val index = historyList.locationToIndex(e.point)
        if (index < 0) {
            return -1
        }
        val bounds = historyList.getCellBounds(index, index) ?: return -1
        return if (bounds.contains(e.point)) index else -1
    }

    private class AgentHistoryCellRenderer : JPanel(BorderLayout()),
        ListCellRenderer<AgentHistoryThreadSummary> {

        private val titleLabel = JBLabel().apply {
            font = JBFont.label().asBold()
        }
        private val previewLabel = JBLabel().apply {
            font = JBFont.small()
        }
        private val metaLabel = JBLabel().apply {
            font = JBFont.small()
        }

        init {
            isOpaque = true
            border = JBUI.Borders.empty(4, 8)

            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(titleLabel, BorderLayout.CENTER)
                add(metaLabel, BorderLayout.EAST)
            }

            val body = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(previewLabel, BorderLayout.CENTER)
            }

            val content = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(header)
                add(body)
            }

            add(content, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out AgentHistoryThreadSummary>,
            value: AgentHistoryThreadSummary,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            titleLabel.text =
                ellipsize(value.title.ifBlank { "Recovered ${value.agentId.take(8)}" }, 44)
            previewLabel.text = ellipsize(
                value.preview.ifBlank { "No assistant response in checkpoint" },
                56
            )
            metaLabel.text = formatTimestamp(value.latestCreatedAt.toString())

            background =
                if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            titleLabel.foreground =
                if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getLabelForeground()
            previewLabel.foreground =
                if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getContextHelpForeground()
            metaLabel.foreground =
                if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getContextHelpForeground()

            return this
        }
    }
}

private fun formatTimestamp(raw: String): String {
    return runCatching {
        val instant = Instant.parse(raw)
        instant.atZone(ZoneId.systemDefault()).format(AgentHistoryListPanel.TIMESTAMP_FORMAT)
    }.getOrElse { raw }
}

private fun ellipsize(value: String, maxChars: Int): String {
    val cleaned = value.replace("\\s+".toRegex(), " ").trim()
    if (cleaned.length <= maxChars) return cleaned
    return cleaned.take(maxChars - 1) + "…"
}
