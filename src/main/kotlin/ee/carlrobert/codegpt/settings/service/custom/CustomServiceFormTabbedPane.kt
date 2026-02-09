package ee.carlrobert.codegpt.settings.service.custom

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.util.ApplicationUtil
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.table.DefaultTableModel

class CustomServiceFormTabbedPane(
    headers: Map<String, String>,
    body: Map<String, *>,
) : JBTabbedPane() {

    private val editorProject: Project = resolveEditorProject()
    private val jsonFileType: FileType =
        FileTypeManager.getInstance().getFileTypeByExtension("json")

    private val headersTable = createHeadersTable(headers)
    private val bodyTable = createBodyTable(body)

    var headers: MutableMap<String, String>
        get() = getHeadersData(headersTable).toMutableMap()
        set(value) {
            setHeadersData(headersTable, value)
        }

    @Suppress("UNCHECKED_CAST")
    var body: MutableMap<String, Any>
        get() = getBodyData(bodyTable) as MutableMap<String, Any>
        set(value) {
            setBodyData(bodyTable, value)
        }

    init {
        tabComponentInsets = JBUI.insetsTop(8)
        addTab("Headers", createHeadersGuidedPanel())
        addTab("Body", createBodyGuidedPanel())
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        headersTable.isEnabled = enabled
        bodyTable.isEnabled = enabled
    }

    private fun createHeadersTable(headers: Map<String, String>): JBTable {
        val model = object : DefaultTableModel(HEADER_COLUMNS, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }

        return JBTable(model).apply {
            visibleRowCount = 6
            setHeadersData(this, headers)
        }
    }

    private fun createBodyTable(body: Map<String, *>): JBTable {
        val model = object : DefaultTableModel(BODY_COLUMNS, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }

        return JBTable(model).apply {
            visibleRowCount = 6
            setBodyData(this, body)
        }
    }

    private fun createHeadersGuidedPanel(): JComponent {
        return ToolbarDecorator.createDecorator(headersTable)
            .setPreferredSize(Dimension(0, JBUI.scale(360)))
            .setAddAction { handleAddHeaderProperty() }
            .setEditAction { handleEditHeaderProperty() }
            .setRemoveAction { removeSelectedHeaderProperty() }
            .addExtraAction(object :
                DumbAwareAction("Edit JSON", "Edit JSON as raw text", jsonFileType.icon) {
                override fun actionPerformed(event: AnActionEvent) {
                    openHeadersJsonDialog()
                }
            })
            .disableUpAction()
            .disableDownAction()
            .createPanel()
    }

    private fun createBodyGuidedPanel(): JComponent {
        return ToolbarDecorator.createDecorator(bodyTable)
            .setPreferredSize(Dimension(0, JBUI.scale(360)))
            .setAddAction { handleAddBodyProperty() }
            .setEditAction { handleEditBodyProperty() }
            .setRemoveAction { removeSelectedBodyProperty() }
            .addExtraAction(object :
                DumbAwareAction("Edit JSON", "Edit JSON as raw text", jsonFileType.icon) {
                override fun actionPerformed(event: AnActionEvent) {
                    openBodyJsonDialog()
                }
            })
            .disableUpAction()
            .disableDownAction()
            .createPanel()
    }

    private fun openHeadersJsonDialog() {
        val dialog = CustomServiceJsonEditorDialog(
            editorProject = editorProject,
            jsonFileType = jsonFileType,
            title = "Edit Headers JSON",
            initialJson = CustomServiceJsonUtils.toPrettyJson(getHeadersData(headersTable)),
            parser = CustomServiceJsonUtils::parseHeadersJson,
        )
        if (dialog.showAndGet()) {
            setHeadersData(headersTable, dialog.value)
        }
    }

    private fun openBodyJsonDialog() {
        val dialog = CustomServiceJsonEditorDialog(
            editorProject = editorProject,
            jsonFileType = jsonFileType,
            title = "Edit Body JSON",
            initialJson = CustomServiceJsonUtils.toPrettyJson(getBodyData(bodyTable)),
            parser = CustomServiceJsonUtils::parseBodyJson,
        )
        if (dialog.showAndGet()) {
            setBodyData(bodyTable, dialog.value)
        }
    }

    private fun setHeadersData(table: JBTable, headers: Map<String, String>) {
        val model = table.model as DefaultTableModel
        if (getHeadersData(table) == headers) {
            return
        }

        model.rowCount = 0
        headers.forEach { (key, value) -> model.addRow(arrayOf(key, value)) }
    }

    private fun getHeadersData(table: JBTable): Map<String, String> {
        val model = table.model as DefaultTableModel
        return buildMap {
            repeat(model.rowCount) { row ->
                val key = model.getValueAt(row, 0).toString().trim()
                if (key.isBlank()) {
                    return@repeat
                }
                put(key, model.getValueAt(row, 1)?.toString().orEmpty())
            }
        }
    }

    private fun setBodyData(table: JBTable, body: Map<String, *>) {
        val model = table.model as DefaultTableModel
        if (getBodyData(table) == body) {
            return
        }

        model.rowCount = 0
        body.forEach { (key, value) ->
            model.addRow(arrayOf(key, CustomServiceJsonUtils.toBodyDisplayValue(value)))
        }
    }

    private fun getBodyData(table: JBTable): MutableMap<String, Any?> {
        val model = table.model as DefaultTableModel
        return buildMap {
            repeat(model.rowCount) { row ->
                val key = model.getValueAt(row, 0).toString().trim()
                if (key.isBlank()) {
                    return@repeat
                }
                val rawValue = model.getValueAt(row, 1)?.toString().orEmpty()
                put(key, CustomServiceJsonUtils.parseBodyValue(rawValue))
            }
        }.toMutableMap()
    }

    private fun handleAddHeaderProperty() {
        val dialog = CustomServiceHeaderPropertyDialog(null, getExistingKeys(headersTable, null))
        if (!dialog.showAndGet()) return
        addOrUpdateHeaderRow(-1, dialog.headerRow)
    }

    private fun handleEditHeaderProperty() {
        val selectedRow = headersTable.selectedRow
        if (selectedRow < 0) return

        val existing = getHeaderRowFromTable(selectedRow)
        val dialog =
            CustomServiceHeaderPropertyDialog(existing, getExistingKeys(headersTable, existing.key))
        if (!dialog.showAndGet()) return

        addOrUpdateHeaderRow(selectedRow, dialog.headerRow)
    }

    private fun removeSelectedHeaderProperty() {
        val selectedRow = headersTable.selectedRow
        if (selectedRow < 0) return
        (headersTable.model as DefaultTableModel).removeRow(selectedRow)
    }

    private fun getHeaderRowFromTable(rowIndex: Int): CustomServiceHeaderRow {
        val model = headersTable.model as DefaultTableModel
        return CustomServiceHeaderRow(
            key = model.getValueAt(rowIndex, 0)?.toString().orEmpty(),
            value = model.getValueAt(rowIndex, 1)?.toString().orEmpty(),
        )
    }

    private fun addOrUpdateHeaderRow(rowIndex: Int, row: CustomServiceHeaderRow) {
        val model = headersTable.model as DefaultTableModel
        if (rowIndex in 0 until model.rowCount) {
            model.setValueAt(row.key, rowIndex, 0)
            model.setValueAt(row.value, rowIndex, 1)
            return
        }

        model.addRow(arrayOf(row.key, row.value))
        val newRow = model.rowCount - 1
        headersTable.selectionModel.setSelectionInterval(newRow, newRow)
    }

    private fun handleAddBodyProperty() {
        val dialog = CustomServiceBodyPropertyDialog(null, getExistingKeys(bodyTable, null))
        if (!dialog.showAndGet()) return
        addOrUpdateBodyRow(-1, dialog.bodyRow)
    }

    private fun handleEditBodyProperty() {
        val selectedRow = bodyTable.selectedRow
        if (selectedRow < 0) return

        val existing = getBodyRowFromTable(selectedRow)
        val dialog =
            CustomServiceBodyPropertyDialog(existing, getExistingKeys(bodyTable, existing.key))
        if (!dialog.showAndGet()) return

        addOrUpdateBodyRow(selectedRow, dialog.bodyRow)
    }

    private fun removeSelectedBodyProperty() {
        val selectedRow = bodyTable.selectedRow
        if (selectedRow < 0) return
        (bodyTable.model as DefaultTableModel).removeRow(selectedRow)
    }

    private fun getBodyRowFromTable(rowIndex: Int): CustomServiceBodyRow {
        val model = bodyTable.model as DefaultTableModel
        val value = model.getValueAt(rowIndex, 1)?.toString().orEmpty()
        return CustomServiceBodyRow(
            key = model.getValueAt(rowIndex, 0)?.toString().orEmpty(),
            type = CustomServiceBodyValueType.infer(CustomServiceJsonUtils.parseBodyValue(value)),
            value = value,
        )
    }

    private fun addOrUpdateBodyRow(rowIndex: Int, row: CustomServiceBodyRow) {
        val model = bodyTable.model as DefaultTableModel
        val normalizedValue = normalizeBodyValueForTable(row.type, row.value)

        if (rowIndex in 0 until model.rowCount) {
            model.setValueAt(row.key, rowIndex, 0)
            model.setValueAt(normalizedValue, rowIndex, 1)
            return
        }

        model.addRow(arrayOf(row.key, normalizedValue))
        val newRow = model.rowCount - 1
        bodyTable.selectionModel.setSelectionInterval(newRow, newRow)
    }

    private fun normalizeBodyValueForTable(
        type: CustomServiceBodyValueType,
        value: String
    ): String {
        if (type == CustomServiceBodyValueType.NULL) return "null"
        return value
    }

    private fun getExistingKeys(table: JBTable, excludedKey: String?): Set<String> {
        val model = table.model as DefaultTableModel
        return buildSet {
            repeat(model.rowCount) { row ->
                val key = model.getValueAt(row, 0)?.toString().orEmpty().trim()
                if (key.isBlank() || key == excludedKey) {
                    return@repeat
                }
                add(key)
            }
        }
    }

    private fun resolveEditorProject(): Project {
        val project = ApplicationUtil.findCurrentProject()
        return if (project != null && !project.isDisposed) project else ProjectManager.getInstance().defaultProject
    }

    private companion object {
        private val HEADER_COLUMNS = arrayOf("Key", "Value")
        private val BODY_COLUMNS = arrayOf("Key", "Value")
    }
}
