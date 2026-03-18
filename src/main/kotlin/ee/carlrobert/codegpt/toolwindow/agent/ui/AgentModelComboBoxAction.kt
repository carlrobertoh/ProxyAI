package ee.carlrobert.codegpt.toolwindow.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgentPreset
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgents
import ee.carlrobert.codegpt.completions.llama.LlamaModel
import ee.carlrobert.codegpt.settings.GeneralSettingsConfigurable
import ee.carlrobert.codegpt.settings.agents.acp.AcpAgentSettings
import ee.carlrobert.codegpt.settings.models.ModelSelection
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelChangeNotifier
import ee.carlrobert.codegpt.settings.service.ModelChangeNotifierAdapter
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.ServiceType.ANTHROPIC
import ee.carlrobert.codegpt.settings.service.ServiceType.CUSTOM_OPENAI
import ee.carlrobert.codegpt.settings.service.ServiceType.GOOGLE
import ee.carlrobert.codegpt.settings.service.ServiceType.INCEPTION
import ee.carlrobert.codegpt.settings.service.ServiceType.LLAMA_CPP
import ee.carlrobert.codegpt.settings.service.ServiceType.MISTRAL
import ee.carlrobert.codegpt.settings.service.ServiceType.OLLAMA
import ee.carlrobert.codegpt.settings.service.ServiceType.OPENAI
import ee.carlrobert.codegpt.settings.service.ServiceType.PROXYAI
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.toolwindow.agent.AgentSession
import ee.carlrobert.codegpt.toolwindow.ui.CodeGPTModelsListPopupAction
import ee.carlrobert.codegpt.toolwindow.ui.ModelListPopups
import java.awt.Color
import javax.swing.Icon
import javax.swing.JComponent

class AgentModelComboBoxAction(
    private val project: Project,
    private val agentSession: AgentSession,
    private val onModelChange: (ServiceType) -> Unit,
    private val onAgentRuntimeChanged: (String?) -> Unit,
    selectedProvider: ServiceType,
    private val availableProviders: List<ServiceType>,
    private val showConfigureModels: Boolean
) : ComboBoxAction() {

    private data class TemplateState(
        val icon: Icon,
        val text: String
    )

    private val modelSettings = ModelSettings.getInstance()

    init {
        isSmallVariant = true
        updateTemplatePresentation(selectedProvider)

        ApplicationManager.getApplication().messageBus.connect().subscribe(
            ModelChangeNotifier.getTopic(),
            object : ModelChangeNotifierAdapter() {
                override fun modelChanged(
                    featureType: FeatureType,
                    newModel: String,
                    serviceType: ServiceType
                ) {
                    if (featureType == FeatureType.AGENT && agentSession.externalAgentId == null) {
                        updateTemplatePresentation(serviceType)
                    }
                }
            }
        )
    }

    fun createCustomComponent(place: String): JComponent {
        return createCustomComponent(templatePresentation, place)
    }

    override fun createCustomComponent(
        presentation: Presentation,
        place: String
    ): JComponent {
        val button = createComboBoxButton(presentation)
        button.foreground = EditorColorsManager.getInstance().globalScheme.defaultForeground
        button.border = null
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        button.putClientProperty(
            "proxyai.refreshPresentation",
            Runnable {
                updateTemplatePresentation(modelSettings.getServiceForFeature(FeatureType.AGENT))
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
        return buildPopupActionGroup((button as ComboBoxButton).presentation)
    }

    override fun shouldShowDisabledActions(): Boolean = true

    private fun buildPopupActionGroup(presentation: Presentation): DefaultActionGroup {
        return DefaultActionGroup().apply {
            addSeparator("Cloud")
            addProxyAIGroup()
            addModelGroup(presentation, ANTHROPIC, "Anthropic", Icons.Anthropic)
            addModelGroup(presentation, OPENAI, "OpenAI", Icons.OpenAI)
            addModelGroup(presentation, CUSTOM_OPENAI, "Custom OpenAI", Icons.OpenAI)
            addModelGroup(presentation, GOOGLE, "Google", Icons.Google)
            addModelGroup(presentation, MISTRAL, "Mistral", Icons.Mistral)
            addInceptionGroup(presentation)
            addOfflineGroups(presentation)
            addExternalAgentsSection()
        }
    }

    private fun DefaultActionGroup.addProxyAIGroup() {
        if (PROXYAI !in availableProviders) {
            return
        }

        val group = DefaultActionGroup.createPopupGroup { "ProxyAI" }
        group.templatePresentation.icon = Icons.DefaultSmall
        group.addAll(proxyAIModelActions().toList())
        add(group)
    }

    private fun proxyAIModelActions(): Array<AnAction> {
        return availableModelsForProvider(PROXYAI)
            .map(::createCodeGPTModelAction)
            .toTypedArray()
    }

    private fun DefaultActionGroup.addModelGroup(
        presentation: Presentation,
        provider: ServiceType,
        label: String,
        icon: Icon
    ) {
        if (provider !in availableProviders) {
            return
        }

        val group = DefaultActionGroup.createPopupGroup { label }
        group.templatePresentation.icon = icon
        availableModelsForProvider(provider).forEach { model ->
            group.add(
                createModelAction(
                    serviceType = provider,
                    label = model.displayName,
                    icon = icon,
                    comboBoxPresentation = presentation
                ) {
                    modelSettings.setModel(FeatureType.AGENT, model.model, provider)
                }
            )
        }
        add(group)
    }

    private fun DefaultActionGroup.addInceptionGroup(presentation: Presentation) {
        if (INCEPTION !in availableProviders) {
            return
        }

        val group = DefaultActionGroup.createPopupGroup { "Inception" }
        group.templatePresentation.icon = Icons.Inception
        group.add(createInceptionModelAction(presentation))
        add(group)
    }

    private fun DefaultActionGroup.addOfflineGroups(presentation: Presentation) {
        if (LLAMA_CPP !in availableProviders && OLLAMA !in availableProviders) {
            return
        }

        addSeparator("Offline")
        if (LLAMA_CPP in availableProviders) {
            add(createLlamaModelAction(presentation))
        }
        if (OLLAMA in availableProviders) {
            add(createOllamaGroup(presentation))
        }
    }

    private fun createOllamaGroup(presentation: Presentation): DefaultActionGroup {
        val group = DefaultActionGroup.createPopupGroup { "Ollama" }
        group.templatePresentation.icon = Icons.Ollama
        ApplicationManager.getApplication()
            .getService(OllamaSettings::class.java)
            .state
            .availableModels
            .forEach { model ->
                group.add(createOllamaModelAction(model, presentation))
            }
        return group
    }

    private fun DefaultActionGroup.addExternalAgentsSection() {
        val externalAgents = availableExternalAgents()
        if (externalAgents.isEmpty() && !showConfigureModels) {
            return
        }

        addSeparator("Agents")
        if (externalAgents.isEmpty()) {
            if (showConfigureModels) {
                add(createNoAgentRuntimesAction())
            }
        } else {
            externalAgents.forEach { preset ->
                add(createAgentRuntimeAction(preset))
            }
        }

        if (showConfigureModels) {
            addSeparator()
            add(createGoToSettingsAction())
        }
    }

    private fun updateTemplatePresentation(selectedService: ServiceType) {
        val externalAgentId = agentSession.externalAgentId
        if (!externalAgentId.isNullOrBlank()) {
            updateExternalAgentPresentation(externalAgentId)
            return
        }

        val templateState = templateState(selectedService)
        templatePresentation.icon = templateState.icon
        templatePresentation.text = templateState.text
    }

    private fun templateState(selectedService: ServiceType): TemplateState {
        val modelCode = selectedAgentModelCode()
        return when (selectedService) {
            PROXYAI -> proxyAITemplateState(modelCode)
            OPENAI -> providerTemplateState(OPENAI, Icons.OpenAI, modelCode)
            CUSTOM_OPENAI -> providerTemplateState(CUSTOM_OPENAI, Icons.OpenAI, modelCode)
            ANTHROPIC -> providerTemplateState(ANTHROPIC, Icons.Anthropic, modelCode)
            LLAMA_CPP -> providerTemplateState(LLAMA_CPP, Icons.Llama, modelCode)
            OLLAMA -> providerTemplateState(OLLAMA, Icons.Ollama, modelCode)
            GOOGLE -> providerTemplateState(
                GOOGLE,
                Icons.Google,
                resolvedGoogleModelCode(modelCode)
            )
            MISTRAL -> providerTemplateState(MISTRAL, Icons.Mistral, modelCode)
            INCEPTION -> providerTemplateState(INCEPTION, Icons.Inception, modelCode)
        }
    }

    private fun proxyAITemplateState(modelCode: String?): TemplateState {
        val state = AgentRuntimeSelectionSupport.compactNativePresentation(
            availableModels = availableModelsForProvider(PROXYAI),
            provider = PROXYAI,
            modelCode = modelCode
        )
        return TemplateState(state.icon ?: Icons.DefaultSmall, state.text)
    }

    private fun providerTemplateState(
        serviceType: ServiceType,
        icon: Icon,
        modelCode: String?
    ): TemplateState {
        val state = AgentRuntimeSelectionSupport.compactNativePresentation(
            availableModels = availableModelsForProvider(serviceType),
            provider = serviceType,
            modelCode = modelCode
        )
        return TemplateState(state.icon ?: icon, state.text)
    }

    private fun selectedAgentModelCode(): String? {
        return modelSettings.getStoredModelForFeature(FeatureType.AGENT)
    }

    private fun updateExternalAgentPresentation(externalAgentId: String) {
        val state = AgentRuntimeSelectionSupport.externalPresentation(externalAgentId)
        templatePresentation.icon = state.icon
        templatePresentation.text = state.text
    }

    private fun createAgentRuntimeAction(preset: ExternalAcpAgentPreset): AnAction {
        return object : DumbAwareAction(
            preset.displayName,
            preset.description,
            externalAgentIcon(preset.id)
        ) {
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = preset.id != agentSession.externalAgentId
            }

            override fun actionPerformed(event: AnActionEvent) {
                agentSession.externalAgentId = preset.id
                agentSession.externalAgentSessionId = null
                onAgentRuntimeChanged(preset.id)
                updateExternalAgentPresentation(preset.id)
                onModelChange(modelSettings.getServiceForFeature(FeatureType.AGENT))
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
    }

    private fun resolvedGoogleModelCode(modelCode: String?): String {
        if (!modelCode.isNullOrBlank()) {
            return modelCode
        }

        return availableModelsForProvider(GOOGLE)
            .firstOrNull()
            ?.model
            .orEmpty()
    }

    private fun llamaCppPresentationText(): String {
        val huggingFaceModel = LlamaSettings.getCurrentState().huggingFaceModel
        val llamaModel = LlamaModel.findByHuggingFaceModel(huggingFaceModel)
        return "%s (%dB)".format(llamaModel.label, huggingFaceModel.parameterSize)
    }

    private fun createModelAction(
        serviceType: ServiceType,
        label: String,
        icon: Icon,
        comboBoxPresentation: Presentation,
        onModelChanged: (() -> Unit)? = null
    ): AnAction {
        return object : DumbAwareAction(label, "", icon) {
            override fun update(event: AnActionEvent) {
                val currentExternalAgent = agentSession.externalAgentId
                event.presentation.isEnabled = when {
                    !currentExternalAgent.isNullOrBlank() -> true
                    else -> event.presentation.text != comboBoxPresentation.text
                }
            }

            override fun actionPerformed(event: AnActionEvent) {
                clearExternalAgentSelection()
                onModelChanged?.invoke()
                handleModelChange(serviceType)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
    }

    private fun handleModelChange(serviceType: ServiceType) {
        updateTemplatePresentation(serviceType)
        onModelChange(serviceType)
    }

    private fun createCodeGPTModelAction(model: ModelSelection): AnAction {
        val selected = isProxyAIModelSelected(model.model) && agentSession.externalAgentId == null
        return CodeGPTModelsListPopupAction(
            model.displayName,
            model.model,
            model.icon ?: Icons.DefaultSmall,
            false,
            selected,
            Runnable {
                clearExternalAgentSelection()
                setAgentModel(PROXYAI, model.model)
                handleModelChange(PROXYAI)
            }
        )
    }

    private fun createOllamaModelAction(model: String, presentation: Presentation): AnAction {
        return createModelAction(
            serviceType = OLLAMA,
            label = model,
            icon = Icons.Ollama,
            comboBoxPresentation = presentation
        ) {
            ApplicationManager.getApplication()
                .getService(OllamaSettings::class.java)
                .state
                .model = model
            setAgentModel(OLLAMA, model)
        }
    }

    private fun createLlamaModelAction(presentation: Presentation): AnAction {
        return createModelAction(
            serviceType = LLAMA_CPP,
            label = llamaCppPresentationText(),
            icon = Icons.Llama,
            comboBoxPresentation = presentation
        ) {
            setAgentModel(
                LLAMA_CPP,
                LlamaSettings.getCurrentState().huggingFaceModel.code
            )
        }
    }

    private fun createInceptionModelAction(presentation: Presentation): AnAction {
        val modelCode = availableModelsForProvider(INCEPTION)
            .firstOrNull()
            ?.model
            ?: "mercury"
        return createModelAction(
            serviceType = INCEPTION,
            label = modelSettings.getModelDisplayName(INCEPTION, modelCode),
            icon = Icons.Inception,
            comboBoxPresentation = presentation
        ) {
            setAgentModel(INCEPTION, modelCode)
        }
    }

    private fun availableExternalAgents(): List<ExternalAcpAgentPreset> {
        return project.service<AcpAgentSettings>().getVisiblePresets(agentSession.externalAgentId)
    }

    private fun createNoAgentRuntimesAction(): DumbAwareAction {
        return object : DumbAwareAction("No Agent Runtimes") {
            override fun actionPerformed(event: AnActionEvent) = Unit

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = false
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
    }

    private fun createGoToSettingsAction(): DumbAwareAction {
        return object : DumbAwareAction("Go to Settings", "", AllIcons.General.Settings) {
            override fun actionPerformed(event: AnActionEvent) {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, GeneralSettingsConfigurable::class.java)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
    }

    private fun externalAgentIcon(externalAgentId: String): Icon {
        return AgentRuntimeSelectionSupport.externalPresentation(externalAgentId).icon
            ?: Icons.DefaultSmall
    }

    private fun availableModelsForFeature(): List<ModelSelection> {
        return modelSettings.getAvailableModels(FeatureType.AGENT)
    }

    private fun availableModelsForProvider(provider: ServiceType): List<ModelSelection> {
        return availableModelsForFeature().filter { it.provider == provider }
    }

    private fun clearExternalAgentSelection() {
        agentSession.externalAgentId = null
        agentSession.externalAgentSessionId = null
        onAgentRuntimeChanged(null)
    }

    private fun setAgentModel(serviceType: ServiceType, modelCode: String) {
        modelSettings.setModel(FeatureType.AGENT, modelCode, serviceType)
    }

    private fun isProxyAIModelSelected(modelCode: String?): Boolean {
        val current = modelSettings.getModelSelection(FeatureType.AGENT) ?: return false
        return current.provider == PROXYAI && modelCode == current.model
    }
}
