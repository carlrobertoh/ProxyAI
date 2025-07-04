package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.DumbAwareAction
import ee.carlrobert.codegpt.settings.configuration.ChatMode
import java.awt.Color
import javax.swing.JComponent

class SearchReplaceToggleAction(
    private val userInputPanel: UserInputPanel
) : ComboBoxAction() {

    init {
        isSmallVariant = true
        updateTemplatePresentation()
    }

    fun createCustomComponent(place: String): JComponent {
        return createCustomComponent(templatePresentation, place)
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val currentMode = getCurrentMode()
        e.presentation.description = buildDynamicTooltip(currentMode)
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val button = createComboBoxButton(presentation)
        button.foreground = EditorColorsManager.getInstance().globalScheme.defaultForeground
        button.border = null
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        return button
    }

    override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
        val actionGroup = DefaultActionGroup()
        ChatMode.entries.forEach { mode ->
            actionGroup.add(createModeAction(mode))
        }
        return actionGroup
    }

    private fun createModeAction(mode: ChatMode): AnAction {
        return object : DumbAwareAction(mode.displayName, mode.description, null) {
            override fun update(event: AnActionEvent) {
                val presentation = event.presentation
                presentation.isEnabledAndVisible = true

                val currentMode = userInputPanel.getChatMode()

                if (mode == currentMode) {
                    presentation.isEnabled = false
                } else if (!mode.isEnabled) {
                    presentation.isEnabled = true
                    presentation.putClientProperty("ActionButton.noBackground", false)
                }
            }

            override fun actionPerformed(e: AnActionEvent) {
                if (!mode.isEnabled) return

                userInputPanel.setChatMode(mode)
                updateTemplatePresentation()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
        }
    }

    private fun updateTemplatePresentation() {
        val currentMode = getCurrentMode()
        templatePresentation.text = currentMode.displayName
        templatePresentation.description = buildDynamicTooltip(currentMode)
    }

    private fun getCurrentMode(): ChatMode = userInputPanel.getChatMode()

    private fun buildDynamicTooltip(currentMode: ChatMode): String {
        return """
            <html>
            <head></head>
            <body>
                <div class="content">
                    <div class="bottom">
                        <b>${currentMode.displayName} Mode</b>
                    </div>
                    <div style="margin-top: 8px; color:#bcbec4;">
                        ${getModeDescription(currentMode)}
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getModeDescription(mode: ChatMode): String {
        return when (mode) {
            ChatMode.ASK ->
                "For general questions, code explanations, and project planning. " +
                        "Provides comprehensive answers with examples."

            ChatMode.EDIT ->
                "For direct code modifications using <code>SEARCH/REPLACE</code> blocks. " +
                        "Makes targeted changes to existing code with minimal impact."

            ChatMode.AGENT ->
                "For autonomous multi-step task execution and complex operations. " +
                        "<span style=\"color:#b3ae60;\">Coming soon.</span>"
        }
    }
}