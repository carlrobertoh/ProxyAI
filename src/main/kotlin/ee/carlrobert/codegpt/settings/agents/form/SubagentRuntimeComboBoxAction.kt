package ee.carlrobert.codegpt.settings.agents.form

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgentPreset
import ee.carlrobert.codegpt.settings.agents.acp.AcpAgentSettings
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.ui.AgentRuntimeSelectionSupport
import ee.carlrobert.codegpt.toolwindow.ui.ModelListPopups
import java.awt.Color
import java.awt.Dimension
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.JComponent

class SubagentRuntimeComboBoxAction(
    private val project: Project,
    private val currentProvider: () -> ServiceType?,
    private val currentModel: () -> String?,
    private val currentExternalAgentId: () -> String?,
    private val onInheritSelected: () -> Unit,
    private val onNativeSelected: (ModelSelection) -> Unit,
    private val onExternalSelected: (String) -> Unit,
) : ComboBoxAction() {

    private val modelSettings = ModelSettings.getInstance()

    init {
        updateTemplatePresentation()
    }

    fun createCustomComponent(place: String): JComponent {
        return createCustomComponent(templatePresentation, place)
    }

    fun refreshPresentation() {
        updateTemplatePresentation()
    }

    override fun createCustomComponent(
        presentation: Presentation,
        place: String
    ): JComponent {
        val button = createComboBoxButton(presentation)
        button.foreground = EditorColorsManager.getInstance().globalScheme.defaultForeground
        button.font = UIManager.getFont("TextField.font") ?: button.font
        button.horizontalAlignment = SwingConstants.LEFT
        button.preferredSize = Dimension(JBUI.scale(280), JBUI.scale(28))
        button.minimumSize = Dimension(JBUI.scale(180), JBUI.scale(28))
        button.border = null
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        button.putClientProperty(
            "proxyai.refreshPresentation",
            Runnable {
                updateTemplatePresentation()
            }
        )
        return button
    }

    override fun createActionPopup(
        group: DefaultActionGroup,
        context: DataContext,
        disposeCallback: Runnable?
    ): JBPopup {
        return ModelListPopups.createPopup(group, context, disposeCallback)
    }

    override fun createPopupActionGroup(
        button: JComponent,
        context: DataContext
    ): DefaultActionGroup {
        val actionGroup = DefaultActionGroup()
        actionGroup.add(createInheritAction())

        val availableProviders = modelSettings.getAvailableProviders(FeatureType.AGENT)
        val availableModels = modelSettings.getAvailableModels(FeatureType.AGENT)

        val cloudProviders = AgentRuntimeSelectionSupport.cloudProviders
            .filter(availableProviders::contains)
        if (cloudProviders.isNotEmpty()) {
            actionGroup.addSeparator("Cloud")
            cloudProviders.forEach { provider ->
                actionGroup.add(createProviderGroup(provider, availableModels))
            }
        }

        val offlineProviders = AgentRuntimeSelectionSupport.offlineProviders
            .filter(availableProviders::contains)
        if (offlineProviders.isNotEmpty()) {
            actionGroup.addSeparator("Offline")
            offlineProviders.forEach { provider ->
                actionGroup.add(createProviderGroup(provider, availableModels))
            }
        }

        val externalAgents = availableExternalAgents()
        if (externalAgents.isNotEmpty()) {
            actionGroup.addSeparator("External agents")
            externalAgents.forEach { preset ->
                actionGroup.add(createExternalAction(preset))
            }
        }

        return actionGroup
    }

    override fun shouldShowDisabledActions(): Boolean = true

    private fun updateTemplatePresentation() {
        val externalAgentId = currentExternalAgentId()
        if (!externalAgentId.isNullOrBlank()) {
            val state = AgentRuntimeSelectionSupport.externalPresentation(externalAgentId)
            templatePresentation.icon = state.icon
            templatePresentation.text = state.text
            return
        }

        val provider = currentProvider()
        val model = currentModel()
        if (provider == null || model.isNullOrBlank()) {
            templatePresentation.icon = null
            templatePresentation.text = "Inherit parent agent"
            return
        }

        val state = AgentRuntimeSelectionSupport.compactNativePresentation(
            availableModels = modelSettings.getAvailableModels(FeatureType.AGENT),
            provider = provider,
            modelCode = model
        )
        templatePresentation.icon = state.icon
        templatePresentation.text = state.text
    }

    private fun createInheritAction(): AnAction {
        val selected = currentProvider() == null &&
                currentModel().isNullOrBlank() &&
                currentExternalAgentId().isNullOrBlank()
        return object : DumbAwareAction(
            "Inherit parent agent",
            "",
            if (selected) AllIcons.Actions.Checked else null
        ) {
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = !selected
            }

            override fun actionPerformed(event: AnActionEvent) {
                onInheritSelected()
                updateTemplatePresentation()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
    }

    private fun createProviderGroup(
        provider: ServiceType,
        availableModels: List<ModelSelection>
    ): DefaultActionGroup {
        val group = DefaultActionGroup.createPopupGroup {
            AgentRuntimeSelectionSupport.groupLabel(provider)
        }
        group.templatePresentation.icon = AgentRuntimeSelectionSupport.iconForProvider(provider)
        availableModels
            .filter { it.provider == provider }
            .forEach { selection ->
                group.add(createNativeAction(selection))
            }
        return group
    }

    private fun createNativeAction(selection: ModelSelection): AnAction {
        val selected = currentExternalAgentId().isNullOrBlank() &&
                currentProvider() == selection.provider &&
                currentModel() == selection.selectionId
        return object : DumbAwareAction(
            selection.displayName,
            "",
            selection.icon ?: AgentRuntimeSelectionSupport.iconForProvider(selection.provider)
        ) {
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = !selected
            }

            override fun actionPerformed(event: AnActionEvent) {
                onNativeSelected(selection)
                updateTemplatePresentation()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
    }

    private fun createExternalAction(preset: ExternalAcpAgentPreset): AnAction {
        val selected = preset.id == currentExternalAgentId()
        val state = AgentRuntimeSelectionSupport.externalPresentation(preset.id)
        return object : DumbAwareAction(
            preset.displayName,
            preset.description,
            state.icon
        ) {
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = !selected
            }

            override fun actionPerformed(event: AnActionEvent) {
                onExternalSelected(preset.id)
                updateTemplatePresentation()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
    }

    private fun availableExternalAgents(): List<ExternalAcpAgentPreset> {
        return project.service<AcpAgentSettings>().getVisiblePresets(currentExternalAgentId())
    }
}
