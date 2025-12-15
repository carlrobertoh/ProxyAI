package ee.carlrobert.codegpt.settings.agents.form

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
import java.awt.Dimension
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

data class SubagentDetails(
    val id: Int,
    var title: String,
    var description: String,
    var tools: MutableSet<SubagentTool>
)

class SubagentDetailsPanel(
    private val readOnlyTools: List<SubagentTool>,
    private val writeTools: List<SubagentTool>
) {
    private val titleField = JBTextField()
    private val descriptionArea = JBTextArea()
    private val allTools = (readOnlyTools + writeTools).distinct()
    private val toolBoxes = allTools.associateWith { JBCheckBox(it.displayName) }

    private var current: SubagentDetails? = null
    private var controlsEnabled = true

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
        descriptionHelp.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        descriptionHelp.font = JBUI.Fonts.smallFont()

        val toolsPanel = javax.swing.JPanel()
        toolsPanel.layout = javax.swing.BoxLayout(toolsPanel, javax.swing.BoxLayout.Y_AXIS)
        allTools.forEach { tool -> toolsPanel.add(toolBoxes.getValue(tool)) }
        val toolsScroll = JBScrollPane(toolsPanel)
        toolsScroll.border = JBUI.Borders.empty(4, 4, 0, 4)

        val headerForm = FormBuilder.createFormBuilder()
            .addComponent(titleLabel)
            .addComponent(wrapWithMargin(titleField, 4, 4, 4, 4), 1)
            .addComponent(descriptionLabel)
            .addComponent(wrapWithMargin(descriptionScroll, 4, 4, 8, 4), 1)
            .addComponent(descriptionHelp)
            .addComponent(JBLabel("Tools"))
            .panel

        toolsScroll.preferredSize = Dimension(0, 0)

        val container = com.intellij.util.ui.components.BorderLayoutPanel(0, 0)
        container.border = JBUI.Borders.empty(8)
        container.addToTop(headerForm)
        container.addToCenter(toolsScroll)
        return container
    }

    private fun wrapWithMargin(component: JComponent, top: Int, left: Int, bottom: Int, right: Int): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(top, left, bottom, right)
        panel.add(component, BorderLayout.CENTER)
        return panel
    }

    fun updateData(details: SubagentDetails) {
        current = details
        titleField.text = details.title
        descriptionArea.text = details.description
        toolBoxes.values.forEach { it.isSelected = false }
        details.tools.forEach { tool -> toolBoxes[tool]?.isSelected = true }
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

    private fun refreshState() {
        val editable = controlsEnabled
        titleField.isEnabled = controlsEnabled
        titleField.isEditable = editable
        descriptionArea.isEnabled = controlsEnabled
        descriptionArea.isEditable = editable
        toolBoxes.values.forEach { it.isEnabled = editable }
    }
}
