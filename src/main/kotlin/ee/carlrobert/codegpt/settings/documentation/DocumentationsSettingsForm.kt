package ee.carlrobert.codegpt.settings.documentation

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import ee.carlrobert.codegpt.CodeGPTBundle
import java.awt.Dimension
import javax.swing.table.DefaultTableModel

class DocumentationsSettingsForm {

    private val tableModel = DefaultTableModel(arrayOf("Name", "URL"), 0)
    private val table = JBTable(tableModel).apply {
        setupTableColumns()
    }
    private val documentationSettings = service<DocumentationSettings>()
    private var originalDocumentations: List<DocumentationDetailsState> = emptyList()

    init {
        setupForm()
    }

    fun createPanel(): DialogPanel {
        return panel {
            row {
                val toolbarDecorator = ToolbarDecorator.createDecorator(table)
                    .setAddAction { handleAddItem() }
                    .setRemoveAction { handleRemoveItem() }
                    .addExtraAction(object :
                        AnAction("Duplicate", "Duplicate documentation", AllIcons.Actions.Copy) {
                        override fun actionPerformed(e: AnActionEvent) {
                            handleDuplicateItem()
                        }
                    })
                    .disableUpDownActions()

                cell(toolbarDecorator.createPanel())
                    .align(Align.FILL)
                    .resizableColumn()
                    .applyToComponent {
                        preferredSize = Dimension(600, 400)
                    }
            }
            row {
                text(CodeGPTBundle.get("settingsConfigurable.documentations.description"))
            }
        }
    }

    fun applyChanges() {
        val newDocumentations = (0 until tableModel.rowCount).map { row ->
            DocumentationDetailsState().apply {
                name = tableModel.getValueAt(row, 0) as String
                url = tableModel.getValueAt(row, 1) as String
            }
        }
        documentationSettings.state.documentations.clear()
        documentationSettings.state.documentations.addAll(newDocumentations)
        originalDocumentations = newDocumentations
    }

    fun isModified(): Boolean {
        val currentDocumentations = (0 until tableModel.rowCount).map { row ->
            DocumentationDetailsState().apply {
                name = tableModel.getValueAt(row, 0) as String
                url = tableModel.getValueAt(row, 1) as String
            }
        }
        return currentDocumentations != originalDocumentations
    }

    fun resetChanges() {
        tableModel.rowCount = 0
        setupForm()
    }

    private fun handleAddItem() {
        addDocumentationToTable(DocumentationDetailsState().apply {
            name = "New Documentation"
            url = "https://example.com"
        })
    }

    private fun handleDuplicateItem() {
        val selectedRow = table.selectedRow
        if (selectedRow != -1) {
            val originalName = tableModel.getValueAt(selectedRow, 0) as String
            val originalUrl = tableModel.getValueAt(selectedRow, 1) as String
            addDocumentationToTable(DocumentationDetailsState().apply {
                name = "$originalName Copy"
                url = originalUrl
            })
        }
    }

    private fun addDocumentationToTable(doc: DocumentationDetailsState) {
        tableModel.addRow(arrayOf(doc.name, doc.url))
        selectLastRowAndUpdateUI()
    }

    private fun selectLastRowAndUpdateUI() {
        val lastRow = table.rowCount - 1
        table.setRowSelectionInterval(lastRow, lastRow)
        scrollToLastRow()
    }

    private fun handleRemoveItem() {
        val selectedRow = table.selectedRow
        if (selectedRow != -1) {
            tableModel.removeRow(selectedRow)

            val newSelectedRow = if (selectedRow > 0) selectedRow - 1 else 0
            if (table.rowCount > 0) {
                table.setRowSelectionInterval(newSelectedRow, newSelectedRow)
            }
        }
    }

    private fun setupForm() {
        originalDocumentations = documentationSettings.state.documentations.toList()
        originalDocumentations.forEach { doc ->
            tableModel.addRow(arrayOf(doc.name, doc.url))
        }
    }

    private fun scrollToLastRow() {
        table.scrollRectToVisible(table.getCellRect(table.rowCount - 1, 0, true))
    }

    private fun JBTable.setupTableColumns() {
        columnModel.apply {
            getColumn(0).preferredWidth = 200
            getColumn(1).preferredWidth = 400
        }
    }
}
