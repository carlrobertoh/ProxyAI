package ee.carlrobert.codegpt.settings.service

import ee.carlrobert.codegpt.settings.service.ModelRole.*
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
        return component.getSelectedService(CHAT_ROLE) != service<GeneralSettings>().state.getSelectedService(CHAT_ROLE)
                || component.getSelectedService(CODECOMPLETION_ROLE) != service<GeneralSettings>().state.getSelectedService(CODECOMPLETION_ROLE)
    }

    override fun apply() {
        val state = service<GeneralSettings>().state
        state.setSelectedService(CHAT_ROLE, component.getSelectedService(CHAT_ROLE))

        val serviceChanged = component.getSelectedService(CHAT_ROLE) != state.selectedService
        if (serviceChanged) {
            resetActiveTab()
            TelemetryAction.SETTINGS_CHANGED.createActionMessage()
                .property("service", component.getSelectedService(CHAT_ROLE).code.lowercase())
                .send()
        }

        state.setSelectedService(CODECOMPLETION_ROLE, component.getSelectedService(CODECOMPLETION_ROLE))
    }

    override fun reset() {
        component.setSelectedService(CHAT_ROLE,service<GeneralSettings>().state.getSelectedService(CHAT_ROLE))
        component.setSelectedService(CODECOMPLETION_ROLE,service<GeneralSettings>().state.getSelectedService(CODECOMPLETION_ROLE))
    }

    private fun resetActiveTab() {
        service<ConversationsState>().currentConversation = null
        val project = findCurrentProject()
            ?: throw RuntimeException("Could not find current project.")
        project.getService(ChatToolWindowContentManager::class.java).resetAll()
    }
}