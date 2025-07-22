package ee.carlrobert.codegpt.ui

import com.intellij.ide.IdeBundle
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.ObservableProperty
import com.intellij.openapi.observable.util.not
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.MinimizeButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.actions.editor.EditCodeSubmissionHandler
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.models.SettingsModelComboBoxAction
import ee.carlrobert.codegpt.settings.service.FeatureType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

data class ObservableProperties(
    val submitted: AtomicBooleanProperty = AtomicBooleanProperty(false),
    val accepted: AtomicBooleanProperty = AtomicBooleanProperty(false),
    val loading: AtomicBooleanProperty = AtomicBooleanProperty(false),
)

class EditCodePopover(private val editor: Editor) {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observableProperties = ObservableProperties()
    private val submissionHandler = EditCodeSubmissionHandler(editor, observableProperties)
    private val promptTextField = JBTextField("", 40).apply {
        emptyText.appendText(CodeGPTBundle.get("editCodePopover.textField.emptyText"))
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    handleSubmit()
                }
            }
        })
    }
    private val popup = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(
            createPopupPanel(),
            promptTextField
        )
        .setTitle(CodeGPTBundle.get("editCodePopover.title"))
        .setMovable(true)
        .setCancelKeyEnabled(true)
        .setCancelOnClickOutside(false)
        .setCancelOnWindowDeactivation(false)
        .setRequestFocus(true)
        .setCancelButton(MinimizeButton(IdeBundle.message("tooltip.hide")))
        .setCancelCallback {
            submissionHandler.handleReject()
            true
        }
        .createPopup()

    fun show() {
        popup.showInBestPositionFor(editor)
    }

    private fun createPopupPanel(): JPanel {
        return panel {
            row {
                cell(promptTextField)
            }
            row {
                comment(CodeGPTBundle.get("editCodePopover.textField.comment"))
            }
            row {
                button(
                    CodeGPTBundle.get("editCodePopover.submitButton.title"),
                    observableProperties.submitted.not(),
                )
                button(
                    CodeGPTBundle.get("editCodePopover.followUpButton.title"),
                    observableProperties.submitted,
                )
                button(CodeGPTBundle.get("editCodePopover.acceptButton.title")) {
                    submissionHandler.handleAccept()
                    popup.cancel()
                }
                    .visibleIf(observableProperties.submitted)
                    .enabledIf(observableProperties.loading.not())
                cell(AsyncProcessIcon("edit_code_spinner")).visibleIf(observableProperties.loading)
                link(CodeGPTBundle.get("shared.discard")) {
                    submissionHandler.handleReject()
                    popup.cancel()
                }
                    .align(AlignX.RIGHT)
                    .visibleIf(observableProperties.submitted)
            }
            separator()
            row {
                text(CodeGPTBundle.get("shared.escToCancel"))
                    .applyToComponent {
                        font = JBUI.Fonts.smallFont()
                    }
                cell(
                    SettingsModelComboBoxAction(
                        FeatureType.EDIT_CODE,
                        ModelSettings.getInstance().getModelSelection(FeatureType.EDIT_CODE),
                        {}
                    ).createCustomComponent(ActionPlaces.UNKNOWN)
                ).align(AlignX.RIGHT)
            }
        }.apply {
            border = JBUI.Borders.empty(8, 8, 2, 8)
        }
    }

    private fun Row.button(title: String, visibleIf: ObservableProperty<Boolean>): Cell<JButton> {
        val button = JButton(title).apply {
            putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
            addActionListener {
                handleSubmit()
            }
        }
        return cell(button)
            .visibleIf(visibleIf)
            .enabledIf(
                EnabledButtonComponentPredicate(
                    button,
                    editor,
                    promptTextField,
                    observableProperties
                )
            )
    }

    private fun handleSubmit() {
        serviceScope.launch {
            submissionHandler.handleSubmit(promptTextField.text)
            promptTextField.text = ""
            promptTextField.emptyText.text =
                CodeGPTBundle.get("editCodePopover.textField.followUp.emptyText")
        }
    }

    private class EnabledButtonComponentPredicate(
        private val button: JButton,
        private val editor: Editor,
        private val promptTextField: JBTextField,
        private val observableProperties: ObservableProperties
    ) : ComponentPredicate() {
        override fun invoke(): Boolean {
            if (!editor.selectionModel.hasSelection()) {
                button.toolTipText = "Please select code to continue"
            }
            if (promptTextField.text.isEmpty()) {
                button.toolTipText = "Please enter a prompt to continue"
            }

            return editor.selectionModel.hasSelection()
                    && promptTextField.text.isNotEmpty()
                    && observableProperties.loading.get().not()
        }

        override fun addListener(listener: (Boolean) -> Unit) {
            promptTextField.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    runInEdt { listener(invoke()) }
                }
            })
            editor.selectionModel.addSelectionListener(object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) {
                    runInEdt { listener(invoke()) }
                }
            })
            observableProperties.loading.afterSet {
                runInEdt { listener(invoke()) }
            }
        }
    }
}