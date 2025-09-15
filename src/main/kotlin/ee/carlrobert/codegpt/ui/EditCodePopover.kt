package ee.carlrobert.codegpt.ui

import com.intellij.icons.AllIcons
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
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.util.MinimizeButton
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.actions.editor.EditCodeHistoryStates
import ee.carlrobert.codegpt.actions.editor.EditCodeSubmissionHandler
import ee.carlrobert.codegpt.actions.editor.HistoryRenderer
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.ModelComboBoxAction
import ee.carlrobert.codegpt.util.ApplicationUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.miginfocom.layout.CC
import net.miginfocom.layout.LC
import net.miginfocom.swing.MigLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
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

    /**
     * Code editing history storage
     */
    private val states = EditCodeHistoryStates.getInstance();

    /**
     * History button, delete button
     */
    private val historyButton: LinkLabel<Void> = LinkLabel<Void>().apply {
        icon = AllIcons.Vcs.History
        disabledIcon = IconLoader.getDisabledIcon(AllIcons.Vcs.History)
        setHoveringIcon(IconUtil.darker(AllIcons.Vcs.History, 3))
        toolTipText = "History"
        setListener({ _, _ -> showHistoryPopup() }, null)
    }
    private val clearButton: LinkLabel<Void> = LinkLabel<Void>().apply {
        icon = AllIcons.Actions.GC
        disabledIcon = IconLoader.getDisabledIcon(AllIcons.Actions.GC)
        setHoveringIcon(IconUtil.darker(AllIcons.Actions.GC, 3))
        toolTipText = "Clear"
        setListener({ _, _ ->
            run {
                promptTextField.text = null
            }
        }, null)
    }

    private var historyShowing: Boolean = false

    val popup = JBPopupFactory.getInstance()
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
                cell(createToolbar(clearButton, historyButton))
                    .align(AlignX.FILL)
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
                    ModelComboBoxAction(
                        ApplicationUtil.findCurrentProject(),
                        {},
                        GeneralSettings.getSelectedService()
                    )
                        .createCustomComponent(ActionPlaces.UNKNOWN)
                ).align(AlignX.RIGHT)
            }
        }.apply {
            border = JBUI.Borders.empty(8, 8, 2, 8)
        }
    }

    private fun createToolbar(vararg buttons: JComponent): JPanel {
        return NonOpaquePanel(migLayout("4")).apply {
            add(JPanel().apply { isOpaque = false }, CC().growX().pushX()) // Left glue
            buttons.iterator().forEach {
                add(it, CC().gapLeft("${JBUIScale.scale(4)}px"))
            }
            border = JBUI.Borders.empty(2, 0, 4, 0)
        }
    }

    private fun showHistoryPopup() {
        return JBPopupFactory.getInstance().createPopupChooserBuilder(states.getHistories())
            .setVisibleRowCount(7)
            .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            .setItemSelectedCallback { promptTextField.text = it }
            .setRenderer(HistoryRenderer())
            .addListener(object : JBPopupListener {
                override fun beforeShown(event: LightweightWindowEvent) {
                    historyShowing = true
                    val popup = event.asPopup()
                    popup.size = Dimension(300, popup.size.height)
                    val relativePoint = RelativePoint(historyButton, Point(0, -JBUI.scale(3)))
                    val screenPoint = Point(relativePoint.screenPoint).apply { translate(0, -popup.size.height) }

                    popup.setLocation(screenPoint)
                }

                override fun onClosed(event: LightweightWindowEvent) {
                    historyShowing = false
                }
            })
            .createPopup()
            .show(historyButton)
    }

    fun migLayout(gapX: String = "0!", gapY: String = "0!", insets: String = "0", lcBuilder: (LC.() -> Unit)? = null) =
        MigLayout(LC().fill().gridGap(gapX, gapY).insets(insets).also { lcBuilder?.invoke(it) })


    fun Row.button(title: String, visibleIf: ObservableProperty<Boolean>): Cell<JButton> {
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
        val text = promptTextField.text
        if (text.isNotBlank()) {
            states.addHistory(text)
        }
        serviceScope.launch {
            submissionHandler.handleSubmit(text)
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
