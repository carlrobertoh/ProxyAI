package ee.carlrobert.codegpt.settings.configuration

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import java.awt.Dimension
import javax.swing.JComponent

class PathInputDialog(
    title: String,
    private val textField: TextFieldWithBrowseButton
) : DialogWrapper(true) {

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                CodeGPTBundle.get("configurationConfigurable.screenshotPaths.dialog.path.label"),
                textField,
                true
            )
            .panel
        panel.preferredSize = Dimension(JBUI.scale(500), panel.preferredSize.height)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = textField
}