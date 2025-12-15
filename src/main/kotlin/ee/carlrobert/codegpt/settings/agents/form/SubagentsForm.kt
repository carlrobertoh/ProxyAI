package ee.carlrobert.codegpt.settings.agents.form

import com.intellij.ui.ToolbarDecorator
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.ui.OverlayUtil
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.agent.SubagentTool
import ee.carlrobert.codegpt.settings.ProxyAISubagent
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.settings.agents.SubagentDefaults
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent

class SubagentsForm(private val project: Project) {
    private val readOnlyTools = SubagentTool.readOnly
    private val writeTools = SubagentTool.write
    private val settingsService = project.service<ProxyAISettingsService>()

    private val listModel = DefaultListModel<SubagentDetails>()
    private val list = JBList(listModel)
    private val detailsPanel = SubagentDetailsPanel(readOnlyTools, writeTools)

    fun createPanel(): JComponent {
        list.cellRenderer = SubagentListCellRenderer()
        list.visibleRowCount = 10
        list.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            detailsPanel.collect()
            list.selectedValue?.let { detailsPanel.updateData(it) }
        }

        listModel.removeAllElements()
        loadSubagents().forEach { listModel.addElement(it) }

        if (listModel.size > 0) list.selectedIndex = 0

        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction {
                val d = SubagentDetails(nextId(), "New Subagent", "", mutableSetOf())
                listModel.addElement(d)
                list.selectedIndex = listModel.size - 1
                detailsPanel.updateData(d)
            }
            .setRemoveAction {
                val idx = list.selectedIndex
                if (idx >= 0) listModel.remove(idx)
                if (listModel.size > 0) list.selectedIndex = 0
            }
            .setRemoveActionUpdater {
                val idx = list.selectedIndex
                idx >= 0 && !isBuiltIn(listModel.getElementAt(idx))
            }
            .addExtraAction(object : AnAction("Generate", "Generate from natural language", AllIcons.Actions.IntentionBulb) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    val input = showGeneratePromptDialog() ?: return
                    list.isEnabled = false
                    detailsPanel.setControlsEnabled(false)
                    var error: String? = null
                    var generated: ee.carlrobert.codegpt.settings.agents.GeneratedSubagent? = null
                    ProgressManager.getInstance().runProcessWithProgressSynchronously({
                        try {
                            generated = ee.carlrobert.codegpt.settings.agents.SubagentGenerator.generateBlocking(input)
                        } catch (t: Throwable) {
                            error = t.message ?: "Generation failed"
                        }
                    }, "Generating subagent...", true, project)

                    val tools = suggestTools(input)
                    runInEdt(ModalityState.any()) {
                        if (generated != null) {
                            val d = SubagentDetails(nextId(), generated!!.title, generated!!.description, tools)
                            listModel.addElement(d)
                            list.selectedIndex = listModel.size - 1
                            detailsPanel.updateData(d)
                            OverlayUtil.showNotification("Subagent generated.")
                        } else if (error != null) {
                            OverlayUtil.showNotification("Failed to generate subagent: $error", com.intellij.notification.NotificationType.ERROR)
                        }
                        list.isEnabled = true
                        detailsPanel.setControlsEnabled(true)
                    }
                }
            })
            .disableUpDownActions()
            .createPanel()
        decorated.preferredSize = Dimension(JBUI.scale(260), 0)

        val rightContainer = BorderLayoutPanel()
        rightContainer.addToCenter(detailsPanel.getPanel())

        return BorderLayoutPanel(8, 0)
            .addToLeft(decorated)
            .addToCenter(rightContainer)
    }

    fun isModified(): Boolean {
        val stored = loadSubagents()
        val form = currentFormStates()
        if (stored.size != form.size) return true
        return stored.zip(form).any { (a, b) -> a != b }
    }

    fun applyChanges() {
        detailsPanel.collect()
        val error = validateEntries()
        if (error != null) throw ConfigurationException(error)
        settingsService.saveSubagents(currentFormStates().map { it.toStored() })
    }

    fun resetChanges() {
        listModel.removeAllElements()
        loadSubagents().forEach { listModel.addElement(it) }
        if (listModel.size > 0) {
            list.selectedIndex = 0
            detailsPanel.updateData(listModel.getElementAt(0))
        }
    }

    private fun currentFormStates(): List<SubagentDetails> {
        detailsPanel.collect()
        return (0 until listModel.size).map { idx -> listModel.getElementAt(idx) }
    }

    private fun loadSubagents(): List<SubagentDetails> {
        return settingsService.getSubagents().map { it.toDetails() }
    }

    private fun isBuiltIn(details: SubagentDetails): Boolean = SubagentDefaults.isBuiltInId(details.id)

    private fun validateEntries(): String? {
        val seen = mutableSetOf<String>()
        for (i in 0 until listModel.size) {
            val entry = listModel.getElementAt(i)
            val title = entry.title.trim()
            if (title.isEmpty()) return "Title is required."
            if (entry.description.trim().isEmpty()) return "Description is required."
            if (entry.tools.isEmpty()) return "Select at least one tool for '$title'."
            val key = title.lowercase()
            if (!seen.add(key)) return "Subagent titles must be unique."
        }
        return null
    }

    private fun nextId(): Int {
        val maxId = (0 until listModel.size).maxOfOrNull { listModel.getElementAt(it).id } ?: 0
        return maxId + 1
    }

    private fun showGeneratePromptDialog(): String? {
        val dialog = GenerateSubagentDialog()
        return if (dialog.showAndGet()) {
            dialog.inputValue.takeIf { it.isNotBlank() }
        } else null
    }
}

private class GenerateSubagentDialog : DialogWrapper(true) {
    private val inputField = JBTextField()
    
    val inputValue: String
        get() = inputField.text
    
    init {
        title = "Generate Subagent"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                label("Describe the subagent:")
            }
            row {
                cell(inputField)
                    .columns(COLUMNS_LARGE)
                    .focused()
            }
            row {
                comment("Example: Review Kotlin code for style and complexity")
            }
        }
    }
    
    override fun doValidate(): ValidationInfo? {
        return if (inputField.text.trim().isEmpty()) {
            ValidationInfo("Description cannot be empty", inputField)
        } else null
    }
}

private fun suggestTools(text: String): MutableSet<SubagentTool> {
    val t = text.lowercase()
    val selected = SubagentTool.readOnly.toMutableSet()
    if (listOf("write", "edit", "fix", "implement", "change", "modify", "apply").any { it in t }) {
        selected.addAll(SubagentTool.write)
    }
    if (listOf("review", "analyze", "audit", "inspect").any { it in t }) {
        selected.removeAll(SubagentTool.write.toSet())
    }
    return selected
}

private fun ProxyAISubagent.toDetails(): SubagentDetails {
    return SubagentDetails(
        id = id,
        title = title,
        description = objective,
        tools = SubagentTool.parse(tools).toMutableSet(),
    )
}

private fun SubagentDetails.toStored(): ProxyAISubagent {
    return ProxyAISubagent(
        id = id,
        title = title,
        objective = description,
        tools = SubagentTool.toStoredValues(tools)
    )
}


private class SubagentListCellRenderer : javax.swing.ListCellRenderer<SubagentDetails> {
    private val label = JBLabel()
    override fun getListCellRendererComponent(
        list: javax.swing.JList<out SubagentDetails>?,
        value: SubagentDetails?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): java.awt.Component {
        label.text = value?.title ?: ""
        label.background = if (isSelected) list?.selectionBackground else list?.background
        label.foreground = if (isSelected) list?.selectionForeground else list?.foreground
        label.isOpaque = true
        label.border = JBUI.Borders.empty(4, 8)
        return label
    }
}
