package ee.carlrobert.codegpt.settings.configuration

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import ee.carlrobert.codegpt.CodeGPTBundle
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class ScreenshotConfigurationForm {

    private val pathListModel = DefaultListModel<String>()
    private val pathList = JBList(pathListModel)

    init {
        pathList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        pathList.visibleRowCount = 3
    }

    fun createPanel(): JPanel {
        val pathPanel = createPathConfigurationPanel()

        return panel {
            row {
                label(CodeGPTBundle.get("configurationConfigurable.screenshotPaths.label"))
            }
            row {
                cell(pathPanel)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .comment(CodeGPTBundle.get("configurationConfigurable.screenshotPaths.comment"))
            }
        }
    }

    private fun createPathConfigurationPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        val decorator = ToolbarDecorator.createDecorator(pathList)
            .setAddAction { addPath() }
            .setRemoveAction { removePath() }
            .setEditAction { editPath() }
            .setAddActionName(CodeGPTBundle.get("configurationConfigurable.screenshotPaths.add"))
            .setRemoveActionName(CodeGPTBundle.get("configurationConfigurable.screenshotPaths.remove"))
            .setEditActionName(CodeGPTBundle.get("configurationConfigurable.screenshotPaths.edit"))

        panel.add(decorator.createPanel(), BorderLayout.CENTER)
        return panel
    }


    private fun addPath() {
        val textField = TextFieldWithBrowseButton()
        val fileChooserDescriptor = FileChooserDescriptor(false, true, false, false, false, false)
        fileChooserDescriptor.title =
            CodeGPTBundle.get("configurationConfigurable.screenshotPaths.chooser.title")
        fileChooserDescriptor.description =
            CodeGPTBundle.get("configurationConfigurable.screenshotPaths.chooser.description")

        textField.addBrowseFolderListener(
            TextBrowseFolderListener(fileChooserDescriptor)
        )

        val dialog = PathInputDialog(
            CodeGPTBundle.get("configurationConfigurable.screenshotPaths.add.title"),
            textField
        )

        if (dialog.showAndGet()) {
            val path = textField.text
            if (path.isNotBlank() && !pathListModel.contains(path)) {
                pathListModel.addElement(path)
            }
        }
    }

    private fun removePath() {
        val selectedIndex = pathList.selectedIndex
        if (selectedIndex >= 0) {
            pathListModel.removeElementAt(selectedIndex)
        }
    }

    private fun editPath() {
        val selectedIndex = pathList.selectedIndex
        if (selectedIndex >= 0) {
            val currentPath = pathListModel.getElementAt(selectedIndex)
            val textField = TextFieldWithBrowseButton()
            textField.text = currentPath

            val fileChooserDescriptor =
                FileChooserDescriptor(false, true, false, false, false, false)
            fileChooserDescriptor.title =
                CodeGPTBundle.get("configurationConfigurable.screenshotPaths.chooser.title")
            fileChooserDescriptor.description =
                CodeGPTBundle.get("configurationConfigurable.screenshotPaths.chooser.description")

            textField.addBrowseFolderListener(
                TextBrowseFolderListener(fileChooserDescriptor)
            )

            val dialog = PathInputDialog(
                CodeGPTBundle.get("configurationConfigurable.screenshotPaths.edit.title"),
                textField
            )

            if (dialog.showAndGet()) {
                val newPath = textField.text
                if (newPath.isNotBlank()) {
                    pathListModel.setElementAt(newPath, selectedIndex)
                }
            }
        }
    }


    fun loadState(paths: List<String>) {
        pathListModel.clear()
        paths.forEach { pathListModel.addElement(it) }
    }

    fun getState(): List<String> {
        val paths = mutableListOf<String>()
        for (i in 0 until pathListModel.size()) {
            paths.add(pathListModel.getElementAt(i))
        }
        return paths
    }
}