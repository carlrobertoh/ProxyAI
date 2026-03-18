package ee.carlrobert.codegpt.toolwindow.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.AnimatedIcon
import ee.carlrobert.codegpt.toolwindow.agent.AcpConfigOption
import ee.carlrobert.codegpt.toolwindow.agent.AcpConfigOptions
import ee.carlrobert.codegpt.toolwindow.agent.AgentSession
import ee.carlrobert.codegpt.toolwindow.ui.ModelListPopups
import java.awt.Color
import javax.swing.Icon
import javax.swing.JComponent

class AgentRuntimeOptionsComboBoxAction(
    private val agentSession: AgentSession,
    private val onAcpConfigChanged: (String, String) -> Unit
) : ComboBoxAction() {

    init {
        isSmallVariant = true
        refreshPresentation(templatePresentation)
    }

    fun createCustomComponent(place: String): JComponent {
        return createCustomComponent(templatePresentation, place)
    }

    override fun createCustomComponent(
        presentation: Presentation,
        place: String
    ): JComponent {
        val button = createComboBoxButton(presentation)
        val livePresentation = button.presentation
        refreshPresentation(livePresentation)
        button.isEnabled = hasExternalAgentSelected()
        button.foreground = EditorColorsManager.getInstance().globalScheme.defaultForeground
        button.border = null
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        button.putClientProperty(
            "proxyai.refreshPresentation",
            Runnable {
                refreshPresentation(livePresentation)
                button.isEnabled = hasExternalAgentSelected()
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
        val errorMessage = agentSession.externalAgentErrorMessage

        when {
            !errorMessage.isNullOrBlank() -> {
                actionGroup.add(createDisabledInfoAction(errorMessage))
            }

            agentSession.externalAgentConfigLoading -> {
                actionGroup.add(createDisabledInfoAction("Loading...", AnimatedIcon.Default()))
            }

            selectableOptions.isEmpty() -> {
                actionGroup.add(createDisabledInfoAction("No options available"))
            }

            else -> {
                selectableOptions.forEach { option ->
                    actionGroup.add(createOptionGroup(option))
                }
            }
        }

        return actionGroup
    }

    override fun shouldShowDisabledActions(): Boolean = true

    private fun createOptionGroup(option: AcpConfigOption): DefaultActionGroup {
        val group = DefaultActionGroup.createPopupGroup { optionLabel(option) }
        option.options.forEach { choice ->
            val selected = choice.value == option.currentValue
            group.add(
                object : DumbAwareAction(
                    choice.name,
                    choice.description,
                    if (selected) AllIcons.Actions.Checked else null
                ) {
                    override fun update(event: AnActionEvent) {
                        event.presentation.isEnabled =
                            !selected && !agentSession.externalAgentConfigLoading
                    }

                    override fun actionPerformed(event: AnActionEvent) {
                        onAcpConfigChanged(option.id, choice.value)
                    }

                    override fun getActionUpdateThread(): ActionUpdateThread {
                        return ActionUpdateThread.BGT
                    }
                }
            )
        }
        return group
    }

    private fun refreshPresentation(presentation: Presentation) {
        presentation.icon = if (agentSession.externalAgentConfigLoading) {
            AnimatedIcon.Default()
        } else {
            null
        }
        presentation.text = buildSummaryText()
    }

    private fun hasExternalAgentSelected(): Boolean {
        return !agentSession.externalAgentId.isNullOrBlank()
    }

    private fun buildSummaryText(): String {
        if (agentSession.externalAgentConfigLoading) {
            return "Loading..."
        }

        agentSession.externalAgentErrorMessage
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        val parts = listOfNotNull(
            AcpConfigOptions.selectedValueName(agentSession.externalAgentConfigOptions, "model"),
            AcpConfigOptions.selectedValueName(agentSession.externalAgentConfigOptions, "thought_level")
        ).ifEmpty {
            listOfNotNull(AcpConfigOptions.selectedValueName(agentSession.externalAgentConfigOptions, "mode"))
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "Options"
    }

    private val selectableOptions: List<AcpConfigOption>
        get() = AcpConfigOptions.selectable(agentSession.externalAgentConfigOptions)

    private fun createDisabledInfoAction(
        text: String,
        icon: Icon? = null
    ): DumbAwareAction {
        return object : DumbAwareAction(text, "", icon) {
            override fun actionPerformed(event: AnActionEvent) = Unit

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = false
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
    }

    private fun optionLabel(option: AcpConfigOption): String = AcpConfigOptions.label(option)
}
