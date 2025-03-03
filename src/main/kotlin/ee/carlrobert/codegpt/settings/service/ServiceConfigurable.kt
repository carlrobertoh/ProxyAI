package ee.carlrobert.codegpt.settings.service

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import ee.carlrobert.codegpt.conversations.ConversationsState
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.telemetry.TelemetryAction
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager
import ee.carlrobert.codegpt.util.ApplicationUtil.findCurrentProject
import javax.swing.JComponent

class ServiceConfigurable : Configurable {

    private lateinit var component: ServiceConfigurableComponent

    override fun getDisplayName(): String {
        return "ProxyAI: Services"
    }

    override fun createComponent(): JComponent {
        component = ServiceConfigurableComponent()
        return component.getPanel()
    }

    override fun isModified(): Boolean {
        return component.getSelectedService() != service<GeneralSettings>().state.selectedService
    }

    override fun apply() {
        val state = service<GeneralSettings>().state
        state.selectedService = component.getSelectedService()

        val serviceChanged = component.getSelectedService() != state.selectedService
        if (serviceChanged) {
            resetActiveTab()
            TelemetryAction.SETTINGS_CHANGED.createActionMessage()
                .property("service", component.getSelectedService().code.lowercase())
                .send()
        }
    }

    override fun reset() {
        component.setSelectedService(service<GeneralSettings>().state.selectedService)
    }

    private fun resetActiveTab() {
        service<ConversationsState>().currentConversation = null
        val project = findCurrentProject()
            ?: throw RuntimeException("Could not find current project.")
        project.getService(ChatToolWindowContentManager::class.java).resetAll()
    }
}