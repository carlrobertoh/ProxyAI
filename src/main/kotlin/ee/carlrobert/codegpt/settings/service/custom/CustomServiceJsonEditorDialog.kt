package ee.carlrobert.codegpt.settings.service.custom

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent

class CustomServiceJsonEditorDialog<T>(
    private val editorProject: Project,
    private val jsonFileType: FileType,
    title: String,
    initialJson: String,
    private val parser: (String) -> T,
) : DialogWrapper(true) {

    private val jsonEditor = EditorTextField("", editorProject, jsonFileType)
    private var parsedValue: T? = null
    private var validationHighlighter: RangeHighlighter? = null

    init {
        this.title = title
        configureJsonEditor(::validateLive)
        jsonEditor.text = initialJson
        validateLive()
        init()
        initValidation()
    }

    val value: T
        get() = checkNotNull(parsedValue)

    override fun createCenterPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addComponent(JBScrollPane(jsonEditor))
            .panel
            .apply {
                val dialogSize = Dimension(JBUI.scale(480), JBUI.scale(300))
                preferredSize = dialogSize
                minimumSize = dialogSize
            }
    }

    override fun doValidate(): ValidationInfo? {
        return parseValidationError(jsonEditor.text)?.let { ValidationInfo(it, jsonEditor) }
    }

    private fun validateLive() {
        val validationError = parseValidationError(jsonEditor.text)
        if (validationError == null) {
            clearValidationHighlight()
        } else {
            applyValidationHighlight(validationError)
        }
    }

    private fun parseValidationError(rawJson: String): String? {
        return try {
            parsedValue = parser(rawJson)
            null
        } catch (exception: IllegalArgumentException) {
            exception.message?.takeIf { it.isNotBlank() } ?: "Malformed JSON."
        }
    }

    private fun configureJsonEditor(listener: () -> Unit) {
        jsonEditor.apply {
            setOneLineMode(false)
            this.preferredSize = Dimension(JBUI.scale(300), JBUI.scale(120))

            addSettingsProvider { editor ->
                editor.settings.apply {
                    isLineNumbersShown = true
                    isRightMarginShown = false
                    isUseSoftWraps = false
                    isWhitespacesShown = false
                    isFoldingOutlineShown = true
                    isAllowSingleLogicalLineFolding = true
                    isAutoCodeFoldingEnabled = true
                }

                (editor as EditorEx).apply {
                    highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
                        editorProject,
                        PsiFileFactory.getInstance(editorProject).createFileFromText(
                            "proxyai-custom-openai.json",
                            jsonFileType,
                            jsonEditor.text,
                            System.currentTimeMillis(),
                            true,
                        ).virtualFile,
                    )
                    isViewer = false
                    backgroundColor = colorsScheme.defaultBackground
                }
            }

            this.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    listener()
                }
            })

            registerProjectReformatShortcut(listener)
        }
    }

    private fun registerProjectReformatShortcut(onReformat: () -> Unit) {
        val shortcutSet = getReformatShortcutSet() ?: return
        if (shortcutSet.shortcuts.isEmpty()) return

        object : DumbAwareAction() {
            override fun actionPerformed(event: AnActionEvent) {
                reformatJsonWithProjectStyle()
                onReformat()
            }
        }.registerCustomShortcutSet(shortcutSet, jsonEditor)
    }

    private fun getReformatShortcutSet(): ShortcutSet? {
        val shortcuts = linkedSetOf<Shortcut>()
        shortcuts += KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_EDITOR_REFORMAT).shortcuts
        shortcuts += KeymapUtil.getActiveKeymapShortcuts("ReformatCode").shortcuts
        if (shortcuts.isEmpty()) return null
        return CustomShortcutSet(*shortcuts.toTypedArray())
    }

    private fun reformatJsonWithProjectStyle() {
        try {
            val psiFile = PsiFileFactory.getInstance(editorProject).createFileFromText(
                "proxyai-custom-openai.json",
                jsonFileType,
                jsonEditor.text,
                System.currentTimeMillis(),
                true,
            )

            WriteCommandAction.runWriteCommandAction(editorProject) {
                CodeStyleManager.getInstance(editorProject).reformat(psiFile)
            }

            jsonEditor.text = psiFile.text
        } catch (_: Exception) {
        }
    }

    private fun applyValidationHighlight(message: String) {
        val editor = jsonEditor.editor as? EditorEx ?: return
        clearValidationHighlight()

        val text = jsonEditor.text
        if (text.isEmpty()) return

        val range = resolveErrorRange(text, message)
        validationHighlighter = editor.markupModel.addRangeHighlighter(
            range.first,
            range.second,
            HighlighterLayer.ERROR + 1,
            TextAttributes().apply {
                effectColor = JBColor.RED
                effectType = EffectType.WAVE_UNDERSCORE
            },
            HighlighterTargetArea.EXACT_RANGE,
        ).apply {
            errorStripeTooltip = message
        }
    }

    private fun clearValidationHighlight() {
        val editor = jsonEditor.editor as? EditorEx ?: return
        validationHighlighter?.let(editor.markupModel::removeHighlighter)
        validationHighlighter = null
    }

    private fun resolveErrorRange(text: String, message: String): Pair<Int, Int> {
        val match = LINE_COLUMN_PATTERN.find(message) ?: return 0 to minOf(text.length, 1)

        val line = match.groupValues[1].toIntOrNull() ?: return 0 to minOf(text.length, 1)
        val column = match.groupValues[2].toIntOrNull() ?: return 0 to minOf(text.length, 1)
        val offset = lineColumnToOffset(text, line, column)
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val highlightEnd = when {
            lineEnd > offset -> lineEnd
            offset < text.length -> offset + 1
            else -> text.length
        }
        return offset to highlightEnd
    }

    private fun lineColumnToOffset(text: String, line: Int, column: Int): Int {
        if (line <= 1) {
            return (column - 1).coerceIn(0, text.length)
        }

        var currentLine = 1
        var offset = 0
        while (currentLine < line && offset < text.length) {
            val nextNewline = text.indexOf('\n', offset)
            if (nextNewline < 0) {
                return text.length
            }
            offset = nextNewline + 1
            currentLine++
        }

        return (offset + column - 1).coerceIn(0, text.length)
    }

    companion object {
        private val LINE_COLUMN_PATTERN = Regex("""line:\s*(\d+),\s*column:\s*(\d+)""")
    }
}
