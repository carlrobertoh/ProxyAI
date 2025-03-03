package ee.carlrobert.codegpt.settings.service.custom

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.settings.service.custom.form.CustomServiceListForm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.swing.JComponent
import kotlin.coroutines.CoroutineContext

object SwingDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        runInEdt {
            block.run()
        }
    }
}

class CustomServiceConfigurable : Configurable {

    private val coroutineScope = CoroutineScope(SupervisorJob() + SwingDispatcher)
    private lateinit var component: CustomServiceListForm

    override fun getDisplayName(): String {
        return "ProxyAI: Custom Service"
    }

    override fun createComponent(): JComponent {
        component = CustomServiceListForm(service<CustomServicesSettings>(), coroutineScope)
        return component.getForm()
    }

    override fun isModified(): Boolean {
        return component.isModified()
    }

    override fun apply() {
        component.applyChanges()
    }

    override fun reset() {
        component.resetForm()
    }

    override fun disposeUIResources() {
        coroutineScope.cancel()
    }
}