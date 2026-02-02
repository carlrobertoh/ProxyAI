package ee.carlrobert.codegpt.settings.hooks.form

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.settings.hooks.GeneratedHookResult
import ee.carlrobert.codegpt.settings.hooks.HookConfig
import ee.carlrobert.codegpt.settings.hooks.HookEventType
import ee.carlrobert.codegpt.settings.hooks.HookScript
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class HookGenerationSummaryDialog(
    private val generatedHook: GeneratedHookResult
) : DialogWrapper(true) {

    private var selectedScriptIndex = 0
    private var selectedEventType: HookEventType = generatedHook.event
    private val editors = mutableMapOf<Int, EditorEx>()
    private val configFields = mutableMapOf<String, String>()

    init {
        title = "Hook Preview"
        setOKButtonText("Add Hook")
        setCancelButtonText("Cancel")
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                cell(
                    JBLabel(
                        "<html><div style='color:#FFFFFF; font-weight:bold'>${
                            escapeHtml(generatedHook.summary)
                        }</div></html>"
                    )
                )
                    .align(AlignX.FILL)
            }.bottomGap(BottomGap.SMALL)

            row("Event:") {
                val eventCombo = JComboBox(HookEventType.entries.map { it.eventName }.toTypedArray()).apply {
                    selectedItem = generatedHook.event.eventName
                    addActionListener {
                        selectedEventType = HookEventType.entries.find { it.eventName == selectedItem as String }!!
                    }
                }
                cell(eventCombo)
                    .align(Align.FILL)
                    .comment("Select the event that triggers this hook")
            }.bottomGap(BottomGap.SMALL)

            separator()
            row {
                label("Configuration")
                    .applyToComponent {
                        font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
                    }
            }.bottomGap(BottomGap.SMALL)

            row("Command:") {
                val commandField = JBTextField(generatedHook.config.command)
                cell(commandField)
                    .align(Align.FILL)
                    .applyToComponent {
                        isEnabled = true
                    }
                commandField.document.addDocumentListener(object : DocumentListener {
                    override fun changedUpdate(e: DocumentEvent?) {
                        configFields["command"] = commandField.text
                    }

                    override fun insertUpdate(e: DocumentEvent?) {
                        configFields["command"] = commandField.text
                    }

                    override fun removeUpdate(e: DocumentEvent?) {
                        configFields["command"] = commandField.text
                    }
                })
            }.bottomGap(BottomGap.SMALL)

            row("Matcher (optional):") {
                val textField = JBTextField(generatedHook.config.matcher ?: "")
                cell(textField).align(Align.FILL)
                textField.document.addDocumentListener(object : DocumentListener {
                    override fun changedUpdate(e: DocumentEvent?) {
                        configFields["matcher"] = textField.text
                    }

                    override fun insertUpdate(e: DocumentEvent?) {
                        configFields["matcher"] = textField.text
                    }

                    override fun removeUpdate(e: DocumentEvent?) {
                        configFields["matcher"] = textField.text
                    }
                })
            }.bottomGap(BottomGap.SMALL)

            row("Timeout (seconds):") {
                val textField = JBTextField(generatedHook.config.timeout?.toString() ?: "5")
                cell(textField).align(Align.FILL)
                textField.document.addDocumentListener(object : DocumentListener {
                    override fun changedUpdate(e: DocumentEvent?) {
                        configFields["timeout"] = textField.text
                    }

                    override fun insertUpdate(e: DocumentEvent?) {
                        configFields["timeout"] = textField.text
                    }

                    override fun removeUpdate(e: DocumentEvent?) {
                        configFields["timeout"] = textField.text
                    }
                })
            }.bottomGap(BottomGap.SMALL)

            separator()
            row {
                label("Generated script")
                    .applyToComponent {
                        font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
                    }
            }.bottomGap(BottomGap.SMALL)

            val script = generatedHook.scripts[selectedScriptIndex]
            val editor = createEditor(script)
            editors[selectedScriptIndex] = editor

            row {
                cell(createEditorPanel(editor))
                    .align(Align.FILL)
                    .resizableColumn()
                    .apply {
                        component.preferredSize = Dimension(JBUI.scale(700), JBUI.scale(300))
                        component.minimumSize = Dimension(JBUI.scale(700), JBUI.scale(150))
                    }
            }.resizableRow()
        }.apply {
            preferredSize = Dimension(600, 500)
            minimumSize = Dimension(500, 400)
        }
    }

    private fun createEditor(script: HookScript): EditorEx {
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(script.language)
        val editorFactor = EditorFactory.getInstance()
        val document = editorFactor.createDocument(script.content)
        val editor =
            editorFactor.createEditor(document, null, fileType, false) as EditorEx

        editor.apply {
            settings.isLineNumbersShown = true
            settings.isWhitespacesShown = false
            settings.isCaretRowShown = true
            settings.isFoldingOutlineShown = true
            colorsScheme = EditorColorsManager.getInstance().globalScheme
            colorsScheme.apply {
                editorFontSize = JBUI.Fonts.label().size
                editorFontName = JBUI.Fonts.label().fontName
            }
            isViewer = true
        }

        return editor
    }

    private fun createEditorPanel(editor: EditorEx): JComponent {
        return JBScrollPane(editor.component).apply {
            border = JBUI.Borders.empty()
        }
    }

    override fun getDimensionServiceKey(): String = "HookGenerationSummaryDialog"

    fun getSelectedHook(): GeneratedHookResult {
        val command = configFields["command"] ?: generatedHook.config.command
        val matcher = configFields["matcher"] ?: generatedHook.config.matcher
        val timeout = configFields["timeout"]?.toIntOrNull() ?: generatedHook.config.timeout

        val updatedConfig = HookConfig(
            command,
            matcher,
            timeout,
            null,
            generatedHook.config.enabled
        )

        return GeneratedHookResult(
            selectedEventType,
            generatedHook.scripts,
            updatedConfig,
            generatedHook.summary
        )
    }

    override fun dispose() {
        editors.values.forEach { editor ->
            EditorFactory.getInstance().releaseEditor(editor)
        }
        super.dispose()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
