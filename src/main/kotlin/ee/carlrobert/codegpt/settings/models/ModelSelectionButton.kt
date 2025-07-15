package ee.carlrobert.codegpt.settings.models

import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class ModelSelectionButton(
    featureType: FeatureType,
    initialModel: ModelSelection?,
    serviceType: ServiceType? = null,
    private val onModelSelected: (ModelSelection) -> Unit,
) : JPanel(BorderLayout()) {

    private val action: SettingsModelComboBoxAction
    private var currentModel: ModelSelection? = initialModel
    private val comboBoxButton: JComponent

    init {
        action = SettingsModelComboBoxAction(
            featureType,
            currentModel,
            { selectedModel ->
                currentModel = selectedModel
                onModelSelected(selectedModel)
            },
            serviceType,
            showConfigureModels = false
        )

        comboBoxButton = action.createCustomComponent("Settings")
        add(comboBoxButton, BorderLayout.CENTER)

        border = JBUI.Borders.customLine(
            JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
            1
        )
    }

    fun setSelectedModel(model: ModelSelection?) {
        currentModel = model
        action.updateTemplatePresentation(model)
    }

    fun getSelectedModel(): ModelSelection? = currentModel

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        comboBoxButton.isEnabled = enabled
    }
}
