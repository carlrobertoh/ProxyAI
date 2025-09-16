package ee.carlrobert.codegpt.settings.service.custom

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.custom.form.CustomServiceForm
import ee.carlrobert.codegpt.util.coroutines.EdtDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.swing.JComponent

class CustomServiceConfigurable : Configurable {

    private val coroutineScope = CoroutineScope(SupervisorJob() + EdtDispatchers.Default)
    private lateinit var component: CustomServiceForm

    override fun getDisplayName(): String {
        return "ProxyAI: Custom Service"
    }

    override fun createComponent(): JComponent {
        component = CustomServiceForm(service<CustomServicesSettings>(), coroutineScope)
        return component.getForm()
    }

    override fun isModified(): Boolean = component.isModified()

    override fun apply() {
        component.applyChanges()
        ModelSelectionService.getInstance()
            .syncWithAvailableCustomOpenAIModels(component.getSelectedServiceId())
    }

    override fun reset() {
        component.resetForm()
    }

    override fun disposeUIResources() {
        coroutineScope.cancel()
    }
}
