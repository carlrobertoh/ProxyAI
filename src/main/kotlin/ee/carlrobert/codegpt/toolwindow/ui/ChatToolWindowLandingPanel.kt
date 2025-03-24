package ee.carlrobert.codegpt.toolwindow.ui

import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.prompts.ChatActionsState
import ee.carlrobert.codegpt.ui.UIUtil.createTextPane
import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.ActionListener
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

class ChatToolWindowLandingPanel(onAction: (LandingPanelAction, Point) -> Unit) : ResponseMessagePanel() {

    init {
        addContent(createContent(onAction))
    }

    private fun createContent(onAction: (LandingPanelAction, Point) -> Unit): JPanel {
        return JPanel(BorderLayout()).apply {
            add(createTextPane(getWelcomeMessage(), false), BorderLayout.NORTH)
            add(createActionsListPanel(onAction), BorderLayout.CENTER)
            add(createTextPane(getCautionMessage(), false), BorderLayout.SOUTH)
        }
    }

    private fun createActionsListPanel(onAction: (LandingPanelAction, Point) -> Unit): JPanel {
        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.PAGE_AXIS)
        listPanel.border = JBUI.Borders.emptyLeft(4)
        listPanel.add(Box.createVerticalStrut(4))
        listPanel.add(createEditorActionLink(LandingPanelAction.EXPLAIN, onAction))
        listPanel.add(Box.createVerticalStrut(4))
        listPanel.add(createEditorActionLink(LandingPanelAction.WRITE_TESTS, onAction))
        listPanel.add(Box.createVerticalStrut(4))
        listPanel.add(createEditorActionLink(LandingPanelAction.FIND_BUGS, onAction))
        listPanel.add(Box.createVerticalStrut(4))
        return listPanel
    }

    private fun createEditorActionLink(
        action: LandingPanelAction,
        onAction: (LandingPanelAction, Point) -> Unit
    ): ActionLink {
        return ActionLink(action.userMessage, ActionListener { event ->
            onAction(action, (event.source as ActionLink).locationOnScreen)
        }).apply {
            icon = Icons.Sparkle
        }
    }

    private fun getWelcomeMessage(): String {
        return """
            <html>
            <p style="margin-top: 4px; margin-bottom: 4px;">
            Hi <strong>${GeneralSettings.getCurrentState().displayName}</strong>, I'm ProxyAI! You can ask me anything, but most people request help with their code. Here are a few examples of what you can ask me:
            </p>
            </html>
        """.trimIndent()
    }

    private fun getCautionMessage(): String {
        return """
            <html>
            <p style="margin-top: 4px; margin-bottom: 4px;">
            I can sometimes make mistakes, so please double-check anything critical.
            </p>
            </html>
        """.trimIndent()
    }
}

enum class LandingPanelAction(
    val label: String,
    val userMessage: String,
    val prompt: String
) {
    FIND_BUGS(
        "Find Bugs",
        "Find bugs in this code",
        ChatActionsState.DEFAULT_FIND_BUGS_PROMPT
    ),
    WRITE_TESTS(
        "Write Tests",
        "Write unit tests for this code",
        ChatActionsState.DEFAULT_WRITE_TESTS_PROMPT
    ),
    EXPLAIN(
        "Explain",
        "Explain the selected code",
        ChatActionsState.DEFAULT_EXPLAIN_PROMPT
    )
}

