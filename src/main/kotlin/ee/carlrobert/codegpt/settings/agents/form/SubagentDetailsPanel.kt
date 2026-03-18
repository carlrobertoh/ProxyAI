package ee.carlrobert.codegpt.settings.agents.form

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.agent.SubagentTool
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgents
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgentService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.agent.AcpConfigOption
import ee.carlrobert.codegpt.toolwindow.agent.AcpConfigOptionChoice
import ee.carlrobert.codegpt.toolwindow.agent.AcpConfigOptions
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class SubagentDetails(
    val id: Int,
    var title: String,
    var description: String,
    var tools: MutableSet<SubagentTool>,
    var provider: ServiceType? = null,
    var model: String? = null,
    var externalAgentId: String? = null,
    var externalAgentOptions: MutableMap<String, String> = linkedMapOf(),
)

class SubagentDetailsPanel(
    private val project: Project,
    private val readOnlyTools: List<SubagentTool>,
    private val writeTools: List<SubagentTool>
) {
    private companion object {
        const val TOOLS_CARD = "tools"
        const val OPTIONS_CARD = "options"
        const val EXTERNAL_OPTION_FIELD_WIDTH = 280
    }

    private val titleField = JBTextField()
    private val descriptionArea = JBTextArea()
    private val allTools = (readOnlyTools + writeTools).distinct()
    private val toolBoxes = allTools.associateWith { JBCheckBox(it.displayName) }
    private val externalAgentService = project.service<ExternalAcpAgentService>()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bodyLayout = CardLayout()
    private val bodyPanel = JPanel(bodyLayout)
    private val toolsSection = JPanel(BorderLayout())
    private val externalOptionsSection = JPanel(BorderLayout())
    private val externalOptionsPanel = JPanel()
    private val externalOptionsStatus = JBLabel("Select an external agent to configure ACP options.")
    private val externalOptionsCache = linkedMapOf<String, List<AcpConfigOption>>()
    private var externalOptionsLoading = false
    private var externalOptionsError: String? = null
    private var externalOptions: List<AcpConfigOption> = emptyList()
    private var loadJob: Job? = null
    private var loadSequence = 0
    private val runtimeAction = SubagentRuntimeComboBoxAction(
        project = project,
        currentProvider = { current?.provider },
        currentModel = { current?.model },
        currentExternalAgentId = { current?.externalAgentId },
        onInheritSelected = {
            current?.apply {
                provider = null
                model = null
                externalAgentId = null
                externalAgentOptions = linkedMapOf()
            }
            clearExternalOptionsState()
            refreshState()
        },
        onNativeSelected = { selection ->
            current?.apply {
                provider = selection.provider
                model = selection.selectionId
                externalAgentId = null
                externalAgentOptions = linkedMapOf()
            }
            clearExternalOptionsState()
            refreshState()
        },
        onExternalSelected = { externalAgentId ->
            val previousExternalAgentId = current?.externalAgentId
            current?.apply {
                provider = null
                model = null
                this.externalAgentId = externalAgentId
                if (previousExternalAgentId != externalAgentId) {
                    externalAgentOptions = linkedMapOf()
                }
            }
            refreshExternalOptions(forceReload = previousExternalAgentId != externalAgentId)
            refreshState()
        }
    )
    private val runtimeComponent = runtimeAction.createCustomComponent(ActionPlaces.UNKNOWN).apply {
        border = JBUI.Borders.customLine(
            JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
            1
        )
    }

    private var current: SubagentDetails? = null
    private var controlsEnabled = true

    init {
        externalOptionsStatus.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        externalOptionsStatus.font = JBUI.Fonts.smallFont()
        externalOptionsStatus.horizontalAlignment = SwingConstants.LEFT
        externalOptionsStatus.alignmentX = Component.LEFT_ALIGNMENT
        externalOptionsStatus.iconTextGap = JBUI.scale(8)
    }

    private fun wrapWithMargin(component: JComponent, top: Int, left: Int, bottom: Int, right: Int): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(top, left, bottom, right)
        panel.add(component, BorderLayout.CENTER)
        return panel
    }

    fun updateData(details: SubagentDetails) {
        loadJob?.cancel()
        current = details
        titleField.text = details.title
        descriptionArea.text = details.description
        toolBoxes.values.forEach { it.isSelected = false }
        details.tools.forEach { tool -> toolBoxes[tool]?.isSelected = true }
        runtimeAction.refreshPresentation()
        refreshExternalOptions()
        refreshState()
    }

    fun collect(): SubagentDetails? {
        val d = current ?: return null
        d.title = titleField.text.trim()
        d.description = descriptionArea.text.trim()
        d.tools = toolBoxes.filter { it.value.isSelected }.keys.toMutableSet()
        return d
    }

    fun setControlsEnabled(enabled: Boolean) {
        controlsEnabled = enabled
        refreshState()
    }

    fun dispose() {
        loadJob?.cancel()
        backgroundScope.cancel()
    }

    private fun refreshState() {
        val editable = controlsEnabled
        val usingExternalAgent = !current?.externalAgentId.isNullOrBlank()
        titleField.isEnabled = controlsEnabled
        titleField.isEditable = editable
        descriptionArea.isEnabled = controlsEnabled
        descriptionArea.isEditable = editable
        runtimeComponent.isEnabled = editable
        toolBoxes.values.forEach { it.isEnabled = editable }
        bodyLayout.show(bodyPanel, if (usingExternalAgent) OPTIONS_CARD else TOOLS_CARD)
        setComponentTreeEnabled(externalOptionsPanel, editable)
        externalOptionsStatus.isEnabled = editable
    }

    fun getPanel(): JPanel {
        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        descriptionArea.margin = JBUI.insets(6, 8)
        val descriptionScroll = ScrollPaneFactory.createScrollPane(descriptionArea, true)
        descriptionScroll.border = IdeBorderFactory.createRoundedBorder()
        descriptionScroll.preferredSize = Dimension(0, JBUI.scale(188))

        val titleLabel = JBLabel("Title")
        val descriptionLabel = JBLabel("Description")
        val descriptionHelp = JBLabel("Describe the subagent's goals and behavior concisely.")
        val runtimeLabel = JBLabel("Agent / Model")
        val runtimeHelp = JBLabel("Inherit the parent agent, pin a native model, or choose an external ACP agent.")
        descriptionHelp.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        descriptionHelp.font = JBUI.Fonts.smallFont()
        runtimeHelp.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        runtimeHelp.font = JBUI.Fonts.smallFont()

        initializeToolsSection()
        initializeExternalOptionsSection()
        bodyPanel.border = JBUI.Borders.emptyTop(16)

        val headerForm = FormBuilder.createFormBuilder()
            .addComponent(titleLabel)
            .addComponent(wrapWithMargin(titleField, 4, 0, 4, 0), 1)
            .addComponent(descriptionLabel)
            .addComponent(wrapWithMargin(descriptionScroll, 4, 0, 8, 0), 1)
            .addComponent(descriptionHelp)
            .addComponent(runtimeLabel)
            .addComponent(wrapWithMargin(runtimeComponent, 4, 0, 4, 0), 1)
            .addComponent(runtimeHelp)
            .panel

        bodyPanel.add(toolsSection, TOOLS_CARD)
        bodyPanel.add(externalOptionsSection, OPTIONS_CARD)

        val container = com.intellij.util.ui.components.BorderLayoutPanel(0, 0)
        container.border = JBUI.Borders.empty(8)
        container.addToTop(headerForm)
        container.addToCenter(bodyPanel)
        return container
    }

    private fun initializeToolsSection() {
        val toolsPanel = JPanel()
        toolsPanel.layout = javax.swing.BoxLayout(toolsPanel, javax.swing.BoxLayout.Y_AXIS)
        allTools.forEach { tool -> toolsPanel.add(toolBoxes.getValue(tool)) }
        val toolsScroll = JBScrollPane(toolsPanel)
        toolsScroll.border = JBUI.Borders.emptyTop(4)
        toolsScroll.preferredSize = Dimension(0, 0)

        toolsSection.removeAll()
        toolsSection.add(JBLabel("Tools"), BorderLayout.NORTH)
        toolsSection.add(toolsScroll, BorderLayout.CENTER)
    }

    private fun initializeExternalOptionsSection() {
        externalOptionsPanel.layout = javax.swing.BoxLayout(externalOptionsPanel, javax.swing.BoxLayout.Y_AXIS)
        externalOptionsPanel.border = JBUI.Borders.emptyTop(6)
        externalOptionsSection.removeAll()

        val optionsHelp = JBLabel("These options come directly from the selected external ACP agent.")
        optionsHelp.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        optionsHelp.font = JBUI.Fonts.smallFont()

        val optionsContainer = JPanel(BorderLayout())
        optionsContainer.border = JBUI.Borders.emptyTop(4)
        optionsContainer.add(externalOptionsPanel, BorderLayout.NORTH)

        val topPanel = JPanel()
        topPanel.layout = javax.swing.BoxLayout(topPanel, javax.swing.BoxLayout.Y_AXIS)
        val optionsLabel = JBLabel("Options").apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        optionsHelp.alignmentX = Component.LEFT_ALIGNMENT
        topPanel.add(optionsLabel)
        topPanel.add(optionsHelp)

        externalOptionsSection.add(topPanel, BorderLayout.NORTH)
        externalOptionsSection.add(optionsContainer, BorderLayout.CENTER)
        rebuildExternalOptionsPanel()
    }

    private fun refreshExternalOptions(forceReload: Boolean = false) {
        val externalAgentId = current?.externalAgentId?.takeIf { it.isNotBlank() }
        if (externalAgentId == null) {
            clearExternalOptionsState()
            rebuildExternalOptionsPanel()
            return
        }

        if (!forceReload) {
            externalOptionsCache[externalAgentId]?.let { cachedOptions ->
                externalOptionsLoading = false
                externalOptionsError = null
                externalOptions = cachedOptions
                normalizeExternalSelections(cachedOptions)
                rebuildExternalOptionsPanel()
                return
            }
        }

        loadJob?.cancel()
        externalOptionsLoading = true
        externalOptionsError = null
        externalOptions = emptyList()
        rebuildExternalOptionsPanel()

        val requestId = ++loadSequence
        val selectionsSnapshot = current?.externalAgentOptions?.toMap().orEmpty()
        loadJob = backgroundScope.launch {
            val result = runCatching {
                externalAgentService.loadConfigOptions(externalAgentId, selectionsSnapshot)
            }

            runInEdt(ModalityState.any()) {
                if (requestId != loadSequence || current?.externalAgentId != externalAgentId) {
                    return@runInEdt
                }
                externalOptionsLoading = false
                result.onSuccess { loadedOptions ->
                    externalOptionsCache[externalAgentId] = loadedOptions
                    externalOptionsError = null
                    externalOptions = loadedOptions
                    normalizeExternalSelections(loadedOptions)
                }.onFailure { error ->
                    if (error is CancellationException) {
                        return@runInEdt
                    }
                    externalOptions = emptyList()
                    externalOptionsError = buildExternalAgentFailureMessage(externalAgentId, error)
                }
                rebuildExternalOptionsPanel()
                refreshState()
            }
        }
    }

    private fun clearExternalOptionsState() {
        loadJob?.cancel()
        externalOptionsLoading = false
        externalOptionsError = null
        externalOptions = emptyList()
    }

    private fun rebuildExternalOptionsPanel() {
        externalOptionsPanel.removeAll()
        externalOptionsStatus.icon = null

        when {
            current?.externalAgentId.isNullOrBlank() -> {
                externalOptionsStatus.text = "Select an external agent to configure ACP options."
                externalOptionsPanel.add(createExternalOptionsStatusRow())
            }

            externalOptionsLoading -> {
                externalOptionsStatus.text = "Loading external agent options..."
                externalOptionsStatus.icon = AnimatedIcon.Default()
                externalOptionsPanel.add(createExternalOptionsStatusRow())
            }

            !externalOptionsError.isNullOrBlank() -> {
                externalOptionsStatus.text = externalOptionsError
                externalOptionsPanel.add(createExternalOptionsStatusRow())
            }

            selectableExternalOptions().isEmpty() -> {
                externalOptionsStatus.text = "This external agent does not expose selectable ACP options."
                externalOptionsPanel.add(createExternalOptionsStatusRow())
            }

            else -> {
                selectableExternalOptions().forEach { option ->
                    externalOptionsPanel.add(createExternalOptionRow(option))
                }
            }
        }

        externalOptionsPanel.revalidate()
        externalOptionsPanel.repaint()
    }

    private fun createExternalOptionsStatusRow(): JComponent {
        return wrapWithMargin(externalOptionsStatus, 0, 0, 0, 0).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun selectableExternalOptions(): List<AcpConfigOption> {
        return AcpConfigOptions.selectable(externalOptions)
    }

    private fun createExternalOptionRow(
        option: AcpConfigOption
    ): JComponent {
        val combo = JComboBox(option.options.toTypedArray())
        combo.font = titleField.font
        combo.preferredSize = Dimension(JBUI.scale(EXTERNAL_OPTION_FIELD_WIDTH), combo.preferredSize.height)
        combo.minimumSize = combo.preferredSize
        combo.renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                val choice = value as? AcpConfigOptionChoice
                return super.getListCellRendererComponent(
                    list,
                    choice?.name ?: value,
                    index,
                    isSelected,
                    cellHasFocus
                )
            }
        }
        combo.selectedItem = AcpConfigOptions.selectedChoice(option, current?.externalAgentOptions.orEmpty())
        combo.isEnabled = controlsEnabled
        combo.addActionListener {
            val selected = combo.selectedItem as? AcpConfigOptionChoice ?: return@addActionListener
            current?.externalAgentOptions?.set(option.id, selected.value)
            if (option.category == "model") {
                current?.externalAgentId?.let { externalOptionsCache.remove(it) }
                refreshExternalOptions()
            }
        }

        val comboPanel = JPanel(BorderLayout())
        comboPanel.alignmentX = Component.LEFT_ALIGNMENT
        comboPanel.add(combo, BorderLayout.WEST)

        val row = JPanel()
        row.layout = javax.swing.BoxLayout(row, javax.swing.BoxLayout.Y_AXIS)
        row.border = JBUI.Borders.empty(0, 0, 10, 0)
        row.alignmentX = Component.LEFT_ALIGNMENT

        val label = JBLabel(AcpConfigOptions.label(option)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        row.add(label)
        option.description?.takeIf { it.isNotBlank() }?.let { description ->
            val help = JBLabel(description)
            help.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            help.font = JBUI.Fonts.smallFont()
            help.alignmentX = Component.LEFT_ALIGNMENT
            row.add(help)
        }
        row.add(
            wrapWithMargin(comboPanel, 4, 0, 0, 0).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
        )
        return row
    }

    private fun normalizeExternalSelections(loadedOptions: List<AcpConfigOption>) {
        val currentDetails = current ?: return
        currentDetails.externalAgentOptions =
            LinkedHashMap(
                AcpConfigOptions.normalizeSelections(loadedOptions, currentDetails.externalAgentOptions)
            )
    }

    private fun buildExternalAgentFailureMessage(
        externalAgentId: String,
        throwable: Throwable
    ): String {
        return ExternalAcpAgents.buildFailureMessage(
            id = externalAgentId,
            throwable = throwable,
            fallbackMessage = "Failed to load ${ExternalAcpAgents.displayName(externalAgentId)} options"
        )
    }

    private fun setComponentTreeEnabled(component: java.awt.Component, enabled: Boolean) {
        component.isEnabled = enabled
        if (component is java.awt.Container) {
            component.components.forEach { child ->
                setComponentTreeEnabled(child, enabled)
            }
        }
    }
}
