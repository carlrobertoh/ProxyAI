package ee.carlrobert.codegpt.toolwindow.chat.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.awt.Component
import java.awt.Container
import javax.swing.JPanel
import javax.swing.JTextPane

class ChatMessageResponseBodyTest : IntegrationTest() {

    fun testToolPanelStartsNewTextPaneForSubsequentStreamedText() {
        ApplicationManager.getApplication().invokeAndWait {
            val disposable = Disposer.newDisposable()
            try {
                val responseBody = ChatMessageResponseBody(project, false, disposable)

                responseBody.updateMessage("Hello")
                responseBody.addToolStatusPanel(JPanel())
                responseBody.updateMessage(" world")

                val textPanes = findComponents(responseBody, JTextPane::class.java)
                assertThat(textPanes).hasSize(2)
                assertThat(textPanes.first().text).contains("Hello")
                assertThat(textPanes.last().text).contains("world")
                assertThat(textPanes.last().text).doesNotContain("Hello")
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

    fun testToolPanelStartsNewCodePanelForSubsequentStreamedCode() {
        ApplicationManager.getApplication().invokeAndWait {
            val disposable = Disposer.newDisposable()
            try {
                val responseBody = ChatMessageResponseBody(project, false, disposable)

                responseBody.updateMessage("```kotlin\nfun main() {\n")
                responseBody.addToolStatusPanel(JPanel())
                responseBody.updateMessage("  println(\"x\")\n}\n```")

                val editorPanels = findComponents(responseBody, ResponseEditorPanel::class.java)
                assertThat(editorPanels).hasSize(2)
                assertThat(editorPanels.first().getEditor()?.document?.text)
                    .contains("fun main() {")
                    .doesNotContain("println(\"x\")")
                assertThat(editorPanels.last().getEditor()?.document?.text)
                    .contains("println(\"x\")")
                    .contains("}")
                    .doesNotContain("fun main() {")

                val textPanes = findComponents(responseBody, JTextPane::class.java)
                assertThat(textPanes).isEmpty()

                editorPanels.forEach { panel ->
                    panel.getEditor()?.let { editor ->
                        EditorFactory.getInstance().releaseEditor(editor)
                    }
                }
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

    fun testTextPaneUsesConfiguredChatFontSize() {
        withChatFontSize(18) {
            ApplicationManager.getApplication().invokeAndWait {
                val disposable = Disposer.newDisposable()
                try {
                    val responseBody = ChatMessageResponseBody(project, false, disposable)
                        .withResponse("Hello")

                    val textPanes = findComponents(responseBody, JTextPane::class.java)
                    assertThat(textPanes).hasSize(1)
                    assertThat(textPanes.first().font.size).isEqualTo(18)
                } finally {
                    Disposer.dispose(disposable)
                }
            }
        }
    }

    fun testExistingTextPaneUpdatesConfiguredChatFontSize() {
        withChatFontSize(16) {
            ApplicationManager.getApplication().invokeAndWait {
                val disposable = Disposer.newDisposable()
                try {
                    val responseBody = ChatMessageResponseBody(project, false, disposable)
                        .withResponse("Hello")

                    GeneralSettings.getCurrentState().chatFontSize = 20
                    responseBody.applyConfiguredFontSize()

                    val textPanes = findComponents(responseBody, JTextPane::class.java)
                    assertThat(textPanes).hasSize(1)
                    assertThat(textPanes.first().font.size).isEqualTo(20)
                } finally {
                    Disposer.dispose(disposable)
                }
            }
        }
    }

    private fun withChatFontSize(chatFontSize: Int, block: () -> Unit) {
        val state = GeneralSettings.getCurrentState()
        val previousFontSize = state.chatFontSize
        state.chatFontSize = chatFontSize
        try {
            block()
        } finally {
            state.chatFontSize = previousFontSize
        }
    }

    private fun <T : Component> findComponents(root: Component, type: Class<T>): List<T> {
        val matches = mutableListOf<T>()

        fun visit(component: Component) {
            if (type.isInstance(component)) {
                matches.add(type.cast(component))
            }
            if (component is Container) {
                component.components.forEach(::visit)
            }
        }

        visit(root)
        return matches
    }
}
