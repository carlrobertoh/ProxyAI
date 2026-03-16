package ee.carlrobert.codegpt.settings.agents.acp

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.agent.external.AcpIcons
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgents
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class AcpAgentSettingsForm(project: Project) {

    private val settings = project.service<AcpAgentSettings>()
    private val selectedPresetIds = linkedSetOf<String>()
    private val presets = ExternalAcpAgents.all()
    private val root = BorderLayoutPanel()
    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "Search ACP runtimes..."
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getTooltipSeparatorColor(), 1),
            JBUI.Borders.empty(3, 8)
        )
        textEditor.border = JBUI.Borders.empty()
    }
    private val tableModel = AcpAgentTableModel()
    private val rowSorter = TableRowSorter(tableModel)
    private val helperLabel = JBLabel(
        "ACP runtimes are external agents that can appear in the Agent runtime dropdown."
    ).apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyTop(6)
    }
    private val table = JBTable(tableModel).apply {
        rowSorter = this@AcpAgentSettingsForm.rowSorter
        emptyText.text = "No ACP runtimes match your search."
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        rowHeight = JBUI.scale(28)
        fillsViewportHeight = true
        tableHeader.reorderingAllowed = false
        tableHeader.resizingAllowed = true
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
        setDefaultRenderer(String::class.java, AcpTableCellRenderer())
    }

    init {
        buildUi()
        configureTable()
        installSearch()
        resetChanges()
    }

    fun createPanel(): JComponent = root

    fun isModified(): Boolean {
        return selectedPresetIds != settings.getEnabledPresetIds().toSet()
    }

    fun applyChanges() {
        settings.setEnabledPresetIds(selectedPresetIds)
    }

    fun resetChanges() {
        selectedPresetIds.clear()
        selectedPresetIds += settings.getEnabledPresetIds()
        tableModel.fireTableDataChanged()
        refreshFilter()
    }

    private fun buildUi() {
        val scrollPane = JBScrollPane(table).apply {
            border = JBUI.Borders.customLine(UIUtil.getTooltipSeparatorColor(), 1)
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        root.border = JBUI.Borders.empty(8)
        root.addToTop(
            BorderLayoutPanel().apply {
                border = JBUI.Borders.emptyBottom(8)
                addToTop(searchField)
                addToCenter(helperLabel)
            }
        )
        root.addToCenter(scrollPane)
    }

    private fun configureTable() {
        rowSorter.setSortable(COL_ENABLED, false)
        rowSorter.setSortable(COL_NAME, false)
        rowSorter.setSortable(COL_VENDOR, false)
        rowSorter.setSortable(COL_COMMAND, false)

        table.columnModel.getColumn(COL_ENABLED).apply {
            minWidth = JBUI.scale(36)
            maxWidth = JBUI.scale(36)
            preferredWidth = JBUI.scale(36)
        }
        table.columnModel.getColumn(COL_NAME).preferredWidth = JBUI.scale(170)
        table.columnModel.getColumn(COL_VENDOR).preferredWidth = JBUI.scale(90)
        table.columnModel.getColumn(COL_COMMAND).preferredWidth = JBUI.scale(360)
    }

    private fun installSearch() {
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                refreshFilter()
            }
        })
    }

    private fun refreshFilter() {
        val query = searchField.text.trim().lowercase()
        rowSorter.rowFilter = if (query.isBlank()) {
            null
        } else {
            object : javax.swing.RowFilter<AcpAgentTableModel, Int>() {
                override fun include(entry: Entry<out AcpAgentTableModel, out Int>): Boolean {
                    val preset = presets[entry.identifier]
                    return listOf(
                        preset.displayName,
                        preset.vendor,
                        preset.description.orEmpty(),
                        preset.fullCommand()
                    ).joinToString(" ").lowercase().contains(query)
                }
            }
        }
    }

    private inner class AcpAgentTableModel : AbstractTableModel() {

        override fun getRowCount(): Int = presets.size

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String {
            return when (column) {
                COL_ENABLED -> ""
                COL_NAME -> "Agent"
                COL_VENDOR -> "Vendor"
                COL_COMMAND -> "Command"
                else -> ""
            }
        }

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == COL_ENABLED) {
                java.lang.Boolean::class.java
            } else {
                String::class.java
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            return columnIndex == COL_ENABLED
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val preset = presets[rowIndex]
            return when (columnIndex) {
                COL_ENABLED -> preset.id in selectedPresetIds
                COL_NAME -> preset.displayName
                COL_VENDOR -> preset.vendor
                COL_COMMAND -> preset.fullCommand()
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != COL_ENABLED) {
                return
            }
            val preset = presets[rowIndex]
            val enabled = aValue as? Boolean ?: false
            if (enabled) {
                selectedPresetIds.add(preset.id)
            } else {
                selectedPresetIds.remove(preset.id)
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    private inner class AcpTableCellRenderer : DefaultTableCellRenderer() {

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component =
                super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
            val modelRow = table.convertRowIndexToModel(row)
            val preset = presets[modelRow]
            horizontalAlignment = LEFT
            border = JBUI.Borders.empty(0, 6)
            icon = if (column == COL_NAME) AcpIcons.iconFor(preset.id) else null
            iconTextGap = JBUI.scale(8)
            font = if (column == COL_NAME) {
                JBFont.label().asBold()
            } else {
                JBFont.small()
            }
            foreground = when {
                isSelected -> table.selectionForeground
                column == COL_NAME -> UIUtil.getLabelForeground()
                else -> UIUtil.getContextHelpForeground()
            }
            return component
        }
    }

    private companion object {
        const val COL_ENABLED = 0
        const val COL_NAME = 1
        const val COL_VENDOR = 2
        const val COL_COMMAND = 3
    }
}
